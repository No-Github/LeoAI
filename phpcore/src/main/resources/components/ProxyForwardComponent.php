<?php
/* Stateful TCP forwarder for the platform-side SOCKS5/HTTP/local-forward engines. */
$pfGet = static function ($value, $key, $default = null) {
    return is_array($value) && array_key_exists($key, $value) ? $value[$key] : $default;
};
$pfAvailable = static function ($name) {
    return function_exists($name) && !in_array($name,
        array_map('trim', explode(',', (string)ini_get('disable_functions'))), true);
};
$pfBase = rtrim((string)sys_get_temp_dir(), '/\\') . DIRECTORY_SEPARATOR
    . '.leo-php-forward-' . substr(hash('sha256', __FILE__), 0, 12);
$pfPath = static function ($connId) use ($pfBase) {
    if (!is_string($connId) || !preg_match('/^[A-Za-z0-9_-]{8,128}$/', $connId)) {
        throw new InvalidArgumentException('invalid connId');
    }
    return $pfBase . DIRECTORY_SEPARATOR . hash('sha256', $connId);
};
$pfReadJson = static function ($path) {
    $value = json_decode((string)@file_get_contents($path), true);
    return is_array($value) ? $value : null;
};
$pfWriteJson = static function ($path, $value) {
    $encoded = json_encode($value);
    if (!is_string($encoded)) return false;
    $temporary = $path . '.' . getmypid() . '.tmp';
    if (@file_put_contents($temporary, $encoded, LOCK_EX) === false) return false;
    @chmod($temporary, 0600);
    return @rename($temporary, $path);
};
$pfAppend = static function ($path, $data) {
    if ($data === '') return 0;
    $written = @file_put_contents($path, $data, FILE_APPEND | LOCK_EX);
    if ($written === false) throw new RuntimeException('proxy queue write failed');
    return $written;
};
$pfTake = static function ($path, $limit) {
    $stream = @fopen($path, 'c+b');
    if ($stream === false) return '';
    if (!@flock($stream, LOCK_EX)) { fclose($stream); return ''; }
    $data = stream_get_contents($stream);
    if (!is_string($data)) $data = '';
    $chunk = substr($data, 0, $limit);
    $rest = (string)substr($data, strlen($chunk));
    ftruncate($stream, 0); rewind($stream);
    if ($rest !== '') fwrite($stream, $rest);
    fflush($stream); flock($stream, LOCK_UN); fclose($stream);
    return $chunk;
};
$pfRemove = static function ($directory) {
    if (!is_dir($directory)) return;
    foreach ((array)glob($directory . DIRECTORY_SEPARATOR . '*') as $path) @unlink($path);
    @rmdir($directory);
};
$pfLaunch = static function ($directory) use ($pfAvailable) {
    $php = defined('PHP_BINARY') && PHP_BINARY !== '' ? PHP_BINARY : 'php';
    $runner = escapeshellarg($php) . ' ' . escapeshellarg(__FILE__)
        . ' --leo-proxy-forward-worker ' . escapeshellarg($directory);
    if (DIRECTORY_SEPARATOR === '\\') {
        if ($pfAvailable('popen')) {
            $handle = @popen('start /B "" ' . $runner . ' >NUL 2>&1', 'r');
            if (is_resource($handle)) pclose($handle);
            return true;
        }
        return false;
    }
    $command = '/bin/sh -c ' . escapeshellarg($runner . ' </dev/null >/dev/null 2>&1 & echo $!');
    if ($pfAvailable('shell_exec')) return (int)trim((string)@shell_exec($command)) > 0;
    if ($pfAvailable('exec')) {
        $lines = []; $code = 1; @exec($command, $lines, $code);
        return $code === 0 && (int)trim((string)end($lines)) > 0;
    }
    if ($pfAvailable('popen')) {
        $handle = @popen($command, 'r');
        if (!is_resource($handle)) return false;
        $pid = (int)trim((string)stream_get_contents($handle)); pclose($handle);
        return $pid > 0;
    }
    return false;
};

$pfWorker = static function ($directory) use ($pfReadJson, $pfWriteJson, $pfAppend, $pfTake) {
    $config = $pfReadJson($directory . DIRECTORY_SEPARATOR . 'config.json');
    if (!is_array($config)) return 2;
    $statusPath = $directory . DIRECTORY_SEPARATOR . 'status.json';
    $address = 'tcp://' . $config['targetHost'] . ':' . (int)$config['targetPort'];
    $errno = 0; $error = '';
    $timeout = max(1, min(30, ((int)$config['connectTimeout']) / 1000));
    $socket = @stream_socket_client($address, $errno, $error, $timeout, STREAM_CLIENT_CONNECT);
    if (!is_resource($socket)) {
        $pfWriteJson($statusPath, ['state' => 'failed', 'msg' => $error !== '' ? $error : (string)$errno]);
        return 3;
    }
    stream_set_blocking($socket, false);
    $pfWriteJson($statusPath, ['state' => 'open', 'pid' => getmypid(), 'updatedAt' => time()]);
    $lastActivity = time(); $stop = $directory . DIRECTORY_SEPARATOR . 'stop';
    $input = $directory . DIRECTORY_SEPARATOR . 'in.queue';
    $output = $directory . DIRECTORY_SEPARATOR . 'out.queue';
    $heartbeat = $directory . DIRECTORY_SEPARATOR . 'heartbeat';
    try {
        while (!is_file($stop) && !feof($socket)) {
            clearstatcache(true, $heartbeat);
            $heartbeatAt = is_file($heartbeat) ? (int)@filemtime($heartbeat) : 0;
            if (time() - max($lastActivity, $heartbeatAt) >= 600) break;
            $outgoing = $pfTake($output, 65536);
            if ($outgoing !== '') {
                $offset = 0; $length = strlen($outgoing);
                while ($offset < $length) {
                    $written = @fwrite($socket, substr($outgoing, $offset));
                    if ($written === false) throw new RuntimeException('socket write failed');
                    if ($written === 0) { usleep(10000); continue; }
                    $offset += $written;
                }
                $lastActivity = time();
            }
            $read = [$socket]; $write = []; $except = [];
            $selected = @stream_select($read, $write, $except, 0, 100000);
            if ($selected === false) throw new RuntimeException('socket select failed');
            if ($selected > 0) {
                $incoming = @fread($socket, 65536);
                if ($incoming === false) throw new RuntimeException('socket read failed');
                if ($incoming !== '') { $pfAppend($input, $incoming); $lastActivity = time(); }
                elseif (feof($socket)) break;
            }
            if (time() % 5 === 0) {
                $pfWriteJson($statusPath, ['state' => 'open', 'pid' => getmypid(), 'updatedAt' => time()]);
            }
        }
        $pfWriteJson($statusPath, ['state' => 'closed', 'pid' => getmypid(), 'updatedAt' => time()]);
    } catch (Exception $error) {
        $pfWriteJson($statusPath, ['state' => 'failed', 'msg' => $error->getMessage(), 'updatedAt' => time()]);
    }
    fclose($socket);
    return 0;
};

if (PHP_SAPI === 'cli' && isset($argv[1]) && $argv[1] === '--leo-proxy-forward-worker') {
    exit($pfWorker(isset($argv[2]) ? $argv[2] : ''));
}

return [
    'id' => 'ProxyForwardComponent', 'version' => '1.0.0',
    'handle' => static function ($action, $params) use (
        $pfGet, $pfBase, $pfPath, $pfReadJson, $pfWriteJson, $pfAppend, $pfTake, $pfRemove, $pfLaunch
    ) {
        $op = (int)$pfGet($params, 'op', -1);
        $connId = (string)$pfGet($params, 'connId', '');
        $directory = $pfPath($connId);
        $statusPath = $directory . DIRECTORY_SEPARATOR . 'status.json';
        if ($op === 0) {
            $host = trim((string)$pfGet($params, 'targetHost', ''));
            $port = (int)$pfGet($params, 'targetPort', 0);
            if ($host === '' || strpos($host, "\0") !== false || $port < 1 || $port > 65535) {
                return ['code' => 400, 'msg' => 'targetHost and targetPort required'];
            }
            if (!is_dir($pfBase) && !@mkdir($pfBase, 0700, true) && !is_dir($pfBase)) {
                return ['code' => 500, 'msg' => 'proxy state directory unavailable'];
            }
            if (is_dir($directory)) {
                $existing = $pfReadJson($statusPath);
                if (is_array($existing) && $existing['state'] === 'open') return ['code' => 409, 'msg' => 'connId exists'];
                $pfRemove($directory);
            }
            if (!@mkdir($directory, 0700, true) && !is_dir($directory)) return ['code' => 500, 'msg' => 'connection state unavailable'];
            @file_put_contents($directory . DIRECTORY_SEPARATOR . 'in.queue', '', LOCK_EX);
            @file_put_contents($directory . DIRECTORY_SEPARATOR . 'out.queue', '', LOCK_EX);
            @touch($directory . DIRECTORY_SEPARATOR . 'heartbeat');
            $timeout = max(100, min(30000, (int)$pfGet($params, 'connectTimeout', 5000)));
            $pfWriteJson($directory . DIRECTORY_SEPARATOR . 'config.json', [
                'targetHost' => $host, 'targetPort' => $port, 'connectTimeout' => $timeout
            ]);
            $pfWriteJson($statusPath, ['state' => 'starting', 'updatedAt' => time()]);
            if (!$pfLaunch($directory)) { $pfRemove($directory); return ['code' => 503, 'msg' => 'background worker unavailable']; }
            $deadline = microtime(true) + ($timeout / 1000) + 1.0;
            do {
                usleep(20000); $status = $pfReadJson($statusPath);
                if (is_array($status) && $status['state'] === 'open') return ['code' => 200, 'msg' => 'opened'];
                if (is_array($status) && $status['state'] === 'failed') {
                    return ['code' => 404, 'msg' => isset($status['msg']) ? $status['msg'] : 'connect failed'];
                }
            } while (microtime(true) < $deadline);
            return ['code' => 504, 'msg' => 'connect timeout'];
        }
        if ($op === 1) {
            $status = $pfReadJson($statusPath);
            if (!is_array($status) || $status['state'] !== 'open') return ['code' => 404, 'msg' => 'connId not found'];
            $data = $pfGet($params, 'data', '');
            if (!is_string($data)) return ['code' => 400, 'msg' => 'data must be binary'];
            return ['code' => 200, 'bytesWritten' => $pfAppend($directory . DIRECTORY_SEPARATOR . 'out.queue', $data)];
        }
        if ($op === 2) {
            if (is_dir($directory)) @touch($directory . DIRECTORY_SEPARATOR . 'heartbeat');
            $data = $pfTake($directory . DIRECTORY_SEPARATOR . 'in.queue', 65536);
            if ($data !== '') return ['code' => 200, 'bytesRead' => strlen($data), 'data' => leo_binary($data)];
            $status = $pfReadJson($statusPath);
            return is_array($status) && in_array($status['state'], ['starting', 'open'], true)
                ? ['code' => 204, 'bytesRead' => 0, 'data' => leo_binary('')]
                : ['code' => 404, 'msg' => 'peer closed'];
        }
        if ($op === 3) {
            if (is_dir($directory)) @file_put_contents($directory . DIRECTORY_SEPARATOR . 'stop', '1', LOCK_EX);
            return ['code' => 200, 'msg' => 'closed'];
        }
        return ['code' => 400, 'msg' => 'unknown op'];
    }
];
