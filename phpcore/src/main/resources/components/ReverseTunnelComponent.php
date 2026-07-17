<?php
/* Remote listener used by the shared reverse-tunnel engine. */
$rtGet = static function ($value, $key, $default = null) {
    return is_array($value) && array_key_exists($key, $value) ? $value[$key] : $default;
};
$rtAvailable = static function ($name) {
    return function_exists($name) && !in_array($name,
        array_map('trim', explode(',', (string)ini_get('disable_functions'))), true);
};
$rtBase = rtrim((string)sys_get_temp_dir(), '/\\') . DIRECTORY_SEPARATOR
    . '.leo-php-reverse-' . substr(hash('sha256', __FILE__), 0, 12);
$rtListenPath = static function ($listenId) use ($rtBase) {
    if (!is_string($listenId) || !preg_match('/^[A-Za-z0-9_-]{8,128}$/', $listenId)) {
        throw new InvalidArgumentException('invalid listenId');
    }
    return $rtBase . DIRECTORY_SEPARATOR . hash('sha256', $listenId);
};
$rtConnPath = static function ($directory, $connId) {
    if (!is_string($connId) || !preg_match('/^[A-Za-z0-9_-]{8,128}$/', $connId)) {
        throw new InvalidArgumentException('invalid connId');
    }
    return $directory . DIRECTORY_SEPARATOR . 'conns' . DIRECTORY_SEPARATOR . hash('sha256', $connId);
};
$rtReadJson = static function ($path) {
    $value = json_decode((string)@file_get_contents($path), true);
    return is_array($value) ? $value : null;
};
$rtWriteJson = static function ($path, $value) {
    $encoded = json_encode($value);
    if (!is_string($encoded)) return false;
    $temporary = $path . '.' . getmypid() . '.tmp';
    if (@file_put_contents($temporary, $encoded, LOCK_EX) === false) return false;
    @chmod($temporary, 0600);
    return @rename($temporary, $path);
};
$rtAppend = static function ($path, $data) {
    if ($data === '') return 0;
    $written = @file_put_contents($path, $data, FILE_APPEND | LOCK_EX);
    if ($written === false) throw new RuntimeException('reverse queue write failed');
    return $written;
};
$rtTake = static function ($path, $limit) {
    $stream = @fopen($path, 'c+b');
    if ($stream === false) return '';
    if (!@flock($stream, LOCK_EX)) { fclose($stream); return ''; }
    $data = stream_get_contents($stream); if (!is_string($data)) $data = '';
    $chunk = substr($data, 0, $limit); $rest = (string)substr($data, strlen($chunk));
    ftruncate($stream, 0); rewind($stream); if ($rest !== '') fwrite($stream, $rest);
    fflush($stream); flock($stream, LOCK_UN); fclose($stream);
    return $chunk;
};
$rtTakeLines = static function ($path, $limit) {
    $raw = '';
    $stream = @fopen($path, 'c+b');
    if ($stream === false) return [];
    if (@flock($stream, LOCK_EX)) {
        $raw = (string)stream_get_contents($stream); ftruncate($stream, 0); rewind($stream);
        fflush($stream); flock($stream, LOCK_UN);
    }
    fclose($stream); $items = [];
    foreach (preg_split('/\r?\n/', $raw) as $line) {
        if ($line === '' || count($items) >= $limit) continue;
        $item = json_decode($line, true); if (is_array($item)) $items[] = $item;
    }
    return $items;
};
$rtRemoveTree = static function ($directory) use (&$rtRemoveTree) {
    if (!is_dir($directory)) { @unlink($directory); return; }
    foreach ((array)glob($directory . DIRECTORY_SEPARATOR . '*') as $path) {
        if (is_dir($path)) $rtRemoveTree($path); else @unlink($path);
    }
    @rmdir($directory);
};
$rtLaunch = static function ($directory) use ($rtAvailable) {
    $php = defined('PHP_BINARY') && PHP_BINARY !== '' ? PHP_BINARY : 'php';
    $runner = escapeshellarg($php) . ' ' . escapeshellarg(__FILE__)
        . ' --leo-reverse-worker ' . escapeshellarg($directory);
    if (DIRECTORY_SEPARATOR === '\\') {
        if ($rtAvailable('popen')) {
            $handle = @popen('start /B "" ' . $runner . ' >NUL 2>&1', 'r');
            if (is_resource($handle)) pclose($handle);
            return true;
        }
        return false;
    }
    $command = '/bin/sh -c ' . escapeshellarg($runner . ' </dev/null >/dev/null 2>&1 & echo $!');
    if ($rtAvailable('shell_exec')) return (int)trim((string)@shell_exec($command)) > 0;
    if ($rtAvailable('exec')) {
        $lines = []; $code = 1; @exec($command, $lines, $code);
        return $code === 0 && (int)trim((string)end($lines)) > 0;
    }
    if ($rtAvailable('popen')) {
        $handle = @popen($command, 'r');
        if (!is_resource($handle)) return false;
        $pid = (int)trim((string)stream_get_contents($handle)); pclose($handle);
        return $pid > 0;
    }
    return false;
};
$rtCloseClient = static function ($connId, &$clients, $paths, $rtWriteJson) {
    if (isset($clients[$connId]) && is_resource($clients[$connId])) @fclose($clients[$connId]);
    unset($clients[$connId]);
    if (isset($paths[$connId])) {
        $rtWriteJson($paths[$connId] . DIRECTORY_SEPARATOR . 'status.json',
            ['state' => 'closed', 'updatedAt' => time()]);
        unset($paths[$connId]);
    }
};
$rtWorker = static function ($directory) use (
    $rtReadJson, $rtWriteJson, $rtAppend, $rtTake, $rtCloseClient
) {
    $config = $rtReadJson($directory . DIRECTORY_SEPARATOR . 'config.json');
    if (!is_array($config)) return 2;
    $bindAddr = (string)$config['bindAddr']; $port = (int)$config['listenPort'];
    $uriHost = strpos($bindAddr, ':') !== false && $bindAddr[0] !== '[' ? '[' . $bindAddr . ']' : $bindAddr;
    $errno = 0; $error = '';
    $server = @stream_socket_server('tcp://' . $uriHost . ':' . $port, $errno, $error,
        STREAM_SERVER_BIND | STREAM_SERVER_LISTEN);
    $statusPath = $directory . DIRECTORY_SEPARATOR . 'status.json';
    if (!is_resource($server)) {
        $rtWriteJson($statusPath, ['state' => 'failed', 'msg' => $error !== '' ? $error : (string)$errno]);
        return 3;
    }
    stream_set_blocking($server, false);
    $name = (string)stream_socket_get_name($server, false); $colon = strrpos($name, ':');
    $actualPort = $colon === false ? $port : (int)substr($name, $colon + 1);
    $rtWriteJson($statusPath, ['state' => 'open', 'pid' => getmypid(), 'listenPort' => $actualPort,
        'bindAddr' => $bindAddr, 'updatedAt' => time()]);
    $clients = []; $paths = []; $lastActivity = time();
    $stop = $directory . DIRECTORY_SEPARATOR . 'stop';
    $heartbeat = $directory . DIRECTORY_SEPARATOR . 'heartbeat';
    $failureMessage = null;
    try {
        while (!is_file($stop)) {
            clearstatcache(true, $heartbeat);
            $heartbeatAt = is_file($heartbeat) ? (int)@filemtime($heartbeat) : 0;
            if (time() - max($lastActivity, $heartbeatAt) >= 600) break;
            $read = [$server]; foreach ($clients as $socket) $read[] = $socket;
            $write = []; $except = []; $selected = @stream_select($read, $write, $except, 0, 100000);
            if ($selected === false) throw new RuntimeException('listener select failed');
            foreach ($read as $ready) {
                if ($ready === $server) {
                    do {
                        $peer = ''; $client = @stream_socket_accept($server, 0, $peer);
                        if (!is_resource($client)) break;
                        stream_set_blocking($client, false);
                        $connId = md5(uniqid((string)mt_rand(), true) . $peer);
                        $connDirectory = $directory . DIRECTORY_SEPARATOR . 'conns' . DIRECTORY_SEPARATOR . hash('sha256', $connId);
                        @mkdir($connDirectory, 0700, true);
                        @file_put_contents($connDirectory . DIRECTORY_SEPARATOR . 'in.queue', '', LOCK_EX);
                        @file_put_contents($connDirectory . DIRECTORY_SEPARATOR . 'out.queue', '', LOCK_EX);
                        $rtWriteJson($connDirectory . DIRECTORY_SEPARATOR . 'status.json', ['state' => 'open', 'updatedAt' => time()]);
                        $clients[$connId] = $client; $paths[$connId] = $connDirectory;
                        $clientAddr = $peer; $clientPort = 0; $lastColon = strrpos($peer, ':');
                        if ($lastColon !== false) { $clientAddr = trim(substr($peer, 0, $lastColon), '[]'); $clientPort = (int)substr($peer, $lastColon + 1); }
                        $rtAppend($directory . DIRECTORY_SEPARATOR . 'accept.queue', json_encode([
                            'connId' => $connId, 'clientAddr' => $clientAddr, 'clientPort' => $clientPort
                        ]) . "\n");
                        $lastActivity = time();
                    } while (true);
                    continue;
                }
                foreach ($clients as $connId => $socket) {
                    if ($socket !== $ready) continue;
                    $data = @fread($socket, 65536);
                    if ($data === false || ($data === '' && feof($socket))) {
                        $rtCloseClient($connId, $clients, $paths, $rtWriteJson);
                    } elseif ($data !== '') {
                        $rtAppend($paths[$connId] . DIRECTORY_SEPARATOR . 'in.queue', $data); $lastActivity = time();
                    }
                    break;
                }
            }
            foreach (array_keys($clients) as $connId) {
                $connDirectory = $paths[$connId];
                if (is_file($connDirectory . DIRECTORY_SEPARATOR . 'stop')) {
                    $rtCloseClient($connId, $clients, $paths, $rtWriteJson); continue;
                }
                $data = $rtTake($connDirectory . DIRECTORY_SEPARATOR . 'out.queue', 65536);
                if ($data === '') continue;
                $offset = 0; $length = strlen($data);
                while ($offset < $length && isset($clients[$connId])) {
                    $written = @fwrite($clients[$connId], substr($data, $offset));
                    if ($written === false) { $rtCloseClient($connId, $clients, $paths, $rtWriteJson); break; }
                    if ($written === 0) { usleep(10000); continue; }
                    $offset += $written; $lastActivity = time();
                }
            }
        }
    } catch (Exception $failure) {
        $failureMessage = $failure->getMessage();
    }
    foreach (array_keys($clients) as $connId) $rtCloseClient($connId, $clients, $paths, $rtWriteJson);
    fclose($server);
    $finalStatus = ['state' => $failureMessage === null ? 'closed' : 'failed',
        'listenPort' => $actualPort, 'bindAddr' => $bindAddr, 'updatedAt' => time()];
    if ($failureMessage !== null) $finalStatus['msg'] = $failureMessage;
    $rtWriteJson($statusPath, $finalStatus);
    return 0;
};

if (PHP_SAPI === 'cli' && isset($argv[1]) && $argv[1] === '--leo-reverse-worker') {
    exit($rtWorker(isset($argv[2]) ? $argv[2] : ''));
}

return [
    'id' => 'ReverseTunnelComponent', 'version' => '1.0.0',
    'handle' => static function ($action, $params) use (
        $rtGet, $rtBase, $rtListenPath, $rtConnPath, $rtReadJson, $rtWriteJson,
        $rtAppend, $rtTake, $rtTakeLines, $rtRemoveTree, $rtLaunch
    ) {
        $op = (int)$rtGet($params, 'op', -1);
        if ($op === 0) {
            $listenId = (string)$rtGet($params, 'listenId', ''); $directory = $rtListenPath($listenId);
            $port = (int)$rtGet($params, 'listenPort', 0); $bindAddr = trim((string)$rtGet($params, 'bindAddr', '127.0.0.1'));
            if ($port < 0 || $port > 65535 || $bindAddr === '' || strpos($bindAddr, "\0") !== false) {
                return ['code' => 400, 'msg' => 'invalid listen address'];
            }
            if (!is_dir($rtBase) && !@mkdir($rtBase, 0700, true) && !is_dir($rtBase)) return ['code' => 500, 'msg' => 'listener state unavailable'];
            if (is_dir($directory)) {
                $current = $rtReadJson($directory . DIRECTORY_SEPARATOR . 'status.json');
                if (is_array($current) && $current['state'] === 'open') return ['code' => 409, 'msg' => 'listenId exists'];
                $rtRemoveTree($directory);
            }
            @mkdir($directory . DIRECTORY_SEPARATOR . 'conns', 0700, true);
            @file_put_contents($directory . DIRECTORY_SEPARATOR . 'accept.queue', '', LOCK_EX);
            @touch($directory . DIRECTORY_SEPARATOR . 'heartbeat');
            $rtWriteJson($directory . DIRECTORY_SEPARATOR . 'config.json', ['listenId' => $listenId, 'listenPort' => $port, 'bindAddr' => $bindAddr]);
            $rtWriteJson($directory . DIRECTORY_SEPARATOR . 'status.json', ['state' => 'starting', 'updatedAt' => time()]);
            if (!$rtLaunch($directory)) { $rtRemoveTree($directory); return ['code' => 503, 'msg' => 'background worker unavailable']; }
            $deadline = microtime(true) + 5.0;
            do {
                usleep(20000); $status = $rtReadJson($directory . DIRECTORY_SEPARATOR . 'status.json');
                if (is_array($status) && $status['state'] === 'open') return ['code' => 200, 'msg' => 'listening',
                    'listenPort' => (int)$status['listenPort'], 'bindAddr' => (string)$status['bindAddr']];
                if (is_array($status) && $status['state'] === 'failed') return ['code' => 500, 'msg' => $status['msg']];
            } while (microtime(true) < $deadline);
            return ['code' => 504, 'msg' => 'listener startup timeout'];
        }
        if ($op === 1 || $op === 2) {
            $listenId = (string)$rtGet($params, 'listenId', ''); $directory = $rtListenPath($listenId);
            $status = $rtReadJson($directory . DIRECTORY_SEPARATOR . 'status.json');
            if (!is_array($status) || $status['state'] !== 'open') return ['code' => 404, 'msg' => 'listenId not found'];
            if ($op === 1) { @file_put_contents($directory . DIRECTORY_SEPARATOR . 'stop', '1', LOCK_EX); return ['code' => 200, 'msg' => 'stopped']; }
            @touch($directory . DIRECTORY_SEPARATOR . 'heartbeat');
            return ['code' => 200, 'newConns' => $rtTakeLines($directory . DIRECTORY_SEPARATOR . 'accept.queue', 256)];
        }
        if ($op >= 3 && $op <= 5) {
            $connId = (string)$rtGet($params, 'connId', '');
            $matches = (array)glob($rtBase . DIRECTORY_SEPARATOR . '*' . DIRECTORY_SEPARATOR . 'conns'
                . DIRECTORY_SEPARATOR . hash('sha256', $connId));
            if (count($matches) === 0) return ['code' => 404, 'msg' => 'connId not found'];
            $directory = $matches[0]; $status = $rtReadJson($directory . DIRECTORY_SEPARATOR . 'status.json');
            if ($op === 3) {
                $data = $rtTake($directory . DIRECTORY_SEPARATOR . 'in.queue', 65536);
                if ($data !== '') return ['code' => 200, 'bytesRead' => strlen($data), 'data' => leo_binary($data)];
                return is_array($status) && $status['state'] === 'open'
                    ? ['code' => 204, 'bytesRead' => 0, 'data' => leo_binary('')]
                    : ['code' => 404, 'msg' => 'peer closed'];
            }
            if ($op === 4) {
                if (!is_array($status) || $status['state'] !== 'open') return ['code' => 404, 'msg' => 'peer closed'];
                $data = $rtGet($params, 'data', ''); if (!is_string($data)) return ['code' => 400, 'msg' => 'data must be binary'];
                return ['code' => 200, 'bytesWritten' => $rtAppend($directory . DIRECTORY_SEPARATOR . 'out.queue', $data)];
            }
            @file_put_contents($directory . DIRECTORY_SEPARATOR . 'stop', '1', LOCK_EX);
            return ['code' => 200, 'msg' => 'closed'];
        }
        if ($op === 6) {
            $listens = [];
            foreach ((array)glob($rtBase . DIRECTORY_SEPARATOR . '*') as $directory) {
                $status = $rtReadJson($directory . DIRECTORY_SEPARATOR . 'status.json');
                $config = $rtReadJson($directory . DIRECTORY_SEPARATOR . 'config.json');
                if (is_array($status) && is_array($config) && $status['state'] === 'open') $listens[] = [
                    'listenId' => $config['listenId'], 'listenPort' => $status['listenPort'], 'bindAddr' => $status['bindAddr']
                ];
            }
            return ['code' => 200, 'listens' => $listens];
        }
        return ['code' => 400, 'msg' => 'unknown op'];
    }
];
