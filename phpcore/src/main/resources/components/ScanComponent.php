<?php
$get = static function ($value, $key, $default = null) {
    return is_array($value) && array_key_exists($key, $value) ? $value[$key] : $default;
};
$available = static function ($name) {
    return function_exists($name) && !in_array($name,
        array_map('trim', explode(',', (string)ini_get('disable_functions'))), true);
};
$scope = substr(hash('sha256', __FILE__ . '|state'), 0, 14);
$workerToken = substr(hash('sha256', __FILE__ . '|worker'), 0, 18);
$base = rtrim((string)sys_get_temp_dir(), '/\\') . DIRECTORY_SEPARATOR . '.' . $scope;
$stateName = static function ($label) {
    return substr(hash('sha256', __FILE__ . '|file|' . $label), 0, 16) . '.dat';
};
$path = static function ($taskId) use ($base) {
    if (!is_string($taskId) || !preg_match('/^[A-Za-z0-9_-]{8,128}$/', $taskId)) throw new InvalidArgumentException('invalid taskId');
    return $base . DIRECTORY_SEPARATOR . $taskId;
};
$readJson = static function ($file) {
    $value = json_decode((string)@file_get_contents($file), true);
    return is_array($value) ? $value : null;
};
$writeJson = static function ($file, $value) {
    $encoded = json_encode($value); if (!is_string($encoded)) return false;
    $temporary = $file . '.' . getmypid() . '.tmp';
    if (@file_put_contents($temporary, $encoded, LOCK_EX) === false) return false;
    @chmod($temporary, 0600); return @rename($temporary, $file);
};
$launch = static function ($directory) use ($available, $workerToken) {
    $php = defined('PHP_BINARY') && PHP_BINARY !== '' ? PHP_BINARY : 'php';
    $runner = escapeshellarg($php) . ' ' . escapeshellarg(__FILE__) . ' ' . escapeshellarg($workerToken) . ' ' . escapeshellarg($directory);
    if (DIRECTORY_SEPARATOR === '\\') {
        if (!$available('popen')) return false;
        $handle = @popen('start /B "" ' . $runner . ' >NUL 2>&1', 'r');
        if (is_resource($handle)) { pclose($handle); return true; }
        return false;
    }
    $command = '/bin/sh -c ' . escapeshellarg($runner . ' </dev/null >/dev/null 2>&1 & echo $!');
    if ($available('shell_exec')) return (int)trim((string)@shell_exec($command)) > 0;
    if ($available('exec')) { $lines = []; $code = 1; @exec($command, $lines, $code); return $code === 0 && (int)trim((string)end($lines)) > 0; }
    if ($available('popen')) { $handle = @popen($command, 'r'); if (!is_resource($handle)) return false; $pid = (int)trim((string)stream_get_contents($handle)); pclose($handle); return $pid > 0; }
    return false;
};
$worker = static function ($directory) use ($readJson, $writeJson, $stateName) {
    $config = $readJson($directory . DIRECTORY_SEPARATOR . $stateName('config'));
    $statusFile = $directory . DIRECTORY_SEPARATOR . $stateName('status');
    if (!is_array($config)) return 2;
    $ports = isset($config['ports']) && is_array($config['ports']) ? $config['ports'] : [];
    $timeout = max(50, min(300000, (int)$config['timeout'])) / 1000.0;
    $status = $readJson($statusFile); if (!is_array($status)) return 3;
    $pendingWrites = 0; $lastWrite = microtime(true);
    foreach ($ports as $port) {
        do {
            $latest = $readJson($statusFile); if (!is_array($latest)) return 4;
            if (isset($latest['status']) && $latest['status'] === 'STOPPED') return 0;
            if (isset($latest['status']) && $latest['status'] === 'PAUSED') { usleep(250000); continue; }
            break;
        } while (true);
        $errno = 0; $error = '';
        $socket = @stream_socket_client('tcp://' . $config['host'] . ':' . (int)$port, $errno, $error, $timeout, STREAM_CLIENT_CONNECT);
        $open = is_resource($socket); if ($open) fclose($socket);
        $latest = $readJson($statusFile); if (is_array($latest)) $status = $latest;
        if ($open) $status['openPortList'][] = (int)$port;
        $status['completedCount'] = isset($status['completedCount']) ? (int)$status['completedCount'] + 1 : 1;
        $status['scannedCount'] = $status['completedCount']; $status['updatedAt'] = (int)round(microtime(true) * 1000);
        $pendingWrites++;
        if ($open || $pendingWrites >= 16 || microtime(true) - $lastWrite >= 0.25) {
            $writeJson($statusFile, $status); $pendingWrites = 0; $lastWrite = microtime(true);
        }
    }
    $status['status'] = 'STOPPED'; $status['finishedAt'] = (int)round(microtime(true) * 1000); $status['updatedAt'] = $status['finishedAt'];
    $writeJson($statusFile, $status); return 0;
};
if (PHP_SAPI === 'cli' && isset($argv[1]) && hash_equals($workerToken, (string)$argv[1])) exit($worker(isset($argv[2]) ? $argv[2] : ''));
$cleanup = static function () use ($base, $readJson, $stateName) {
    if (!is_dir($base)) return 0;
    $survivors = [];
    foreach ((array)glob($base . DIRECTORY_SEPARATOR . '*') as $directory) {
        if (!is_dir($directory)) { @unlink($directory); continue; }
        $status = $readJson($directory . DIRECTORY_SEPARATOR . $stateName('status'));
        $state = is_array($status) && isset($status['status']) ? (string)$status['status'] : '';
        $milliseconds = is_array($status) && isset($status['finishedAt']) ? (int)$status['finishedAt']
            : (is_array($status) && isset($status['updatedAt']) ? (int)$status['updatedAt']
            : (is_array($status) && isset($status['createdAt']) ? (int)$status['createdAt'] : 0));
        $time = $milliseconds > 0 ? (int)($milliseconds / 1000) : (int)@filemtime($directory);
        $ttl = $state === 'STOPPED' ? 1800 : ($state === '' ? 300 : 86400);
        if ($time > 0 && time() - $time > $ttl) {
            foreach ((array)glob($directory . DIRECTORY_SEPARATOR . '*') as $file) @unlink($file);
            @rmdir($directory); continue;
        }
        $survivors[] = ['directory' => $directory, 'state' => $state, 'time' => $time];
    }
    usort($survivors, static function ($left, $right) { return $left['time'] - $right['time']; });
    $remove = max(0, count($survivors) - 64);
    foreach ($survivors as $index => $item) {
        if ($remove <= 0 || $item['state'] !== 'STOPPED') continue;
        foreach ((array)glob($item['directory'] . DIRECTORY_SEPARATOR . '*') as $file) @unlink($file);
        if (@rmdir($item['directory'])) { unset($survivors[$index]); $remove--; }
    }
    return count($survivors);
};
$ping = static function ($host, $timeout, $windows) use ($available) {
    if (!preg_match('/^[A-Za-z0-9._:%-]+$/', $host)) return false;
    $seconds = max(1, (int)ceil($timeout / 1000));
    $command = $windows ? 'ping -n 1 -w ' . max(1, $timeout) . ' ' . escapeshellarg($host)
        : 'ping -c 1 -W ' . $seconds . ' ' . escapeshellarg($host);
    if ($available('exec')) { $lines = []; $code = 1; @exec($command . ' 2>&1', $lines, $code); return $code === 0; }
    if ($available('shell_exec')) { $output = @shell_exec($command . ' 2>&1'); return is_string($output) && preg_match('/(?:1 received|1 packets received|TTL=)/i', $output); }
    return false;
};
return [
    'id' => 'ScanComponent', 'version' => '1.0.0',
    'handle' => static function ($action, $params) use ($get, $base, $path, $stateName, $readJson, $writeJson, $launch, $cleanup, $ping) {
        $taskCount = $cleanup();
        if ($action === 'reachable') {
            $hosts = $get($params, 'scanHosts', []); if (!is_array($hosts) || !$hosts) return ['code' => 400, 'msg' => 'scanHosts required'];
            $timeout = max(1, min(300000, (int)$get($params, 'scanTimeout', 3000))); $reachable = []; $unreachable = [];
            foreach (array_slice($hosts, 0, 1024) as $host) {
                $host = trim((string)$host); if ($host === '') return ['code' => 400, 'msg' => 'scanHosts contains empty host'];
                if ($ping($host, $timeout, DIRECTORY_SEPARATOR === '\\')) $reachable[] = $host; else $unreachable[] = $host;
            }
            return ['code' => 200, 'reachableHostList' => $reachable, 'unreachableHostList' => $unreachable,
                'totalCount' => count($reachable) + count($unreachable), 'reachableCount' => count($reachable),
                'unreachableCount' => count($unreachable), 'pendingCount' => 0, 'timedOut' => false];
        }
        if ($action === 'start') {
            if ($taskCount >= 64) return ['code' => 429, 'msg' => 'scan task limit reached'];
            $host = trim((string)$get($params, 'scanHost', '')); $ports = $get($params, 'scanPorts', []);
            if ($host === '' || strlen($host) > 255 || strpos($host, "\0") !== false) return ['code' => 400, 'msg' => 'scanHost required'];
            if (!is_array($ports) || !$ports || count($ports) > 65535) return ['code' => 400, 'msg' => 'scanPorts required'];
            $normalized = [];
            foreach ($ports as $port) { $port = (int)$port; if ($port < 1 || $port > 65535) return ['code' => 400, 'msg' => 'invalid port']; $normalized[$port] = $port; }
            $normalized = array_values($normalized); $taskId = substr(hash('sha256', uniqid('', true) . '|' . mt_rand()), 0, 32); $directory = $path($taskId);
            if (!is_dir($base) && !@mkdir($base, 0700, true) && !is_dir($base)) return ['code' => 500, 'msg' => 'scan state directory unavailable'];
            if (!@mkdir($directory, 0700, true) && !is_dir($directory)) return ['code' => 500, 'msg' => 'scan task directory unavailable'];
            $now = (int)round(microtime(true) * 1000); $timeout = max(50, min(300000, (int)$get($params, 'scanTimeout', 3000)));
            $writeJson($directory . DIRECTORY_SEPARATOR . $stateName('config'), ['host' => $host, 'ports' => $normalized, 'timeout' => $timeout]);
            $writeJson($directory . DIRECTORY_SEPARATOR . $stateName('status'), ['taskId' => $taskId, 'status' => 'RUNNING',
                'portLength' => count($normalized), 'openPortList' => [], 'completedCount' => 0, 'scannedCount' => 0,
                'createdAt' => $now, 'updatedAt' => $now]);
            if (!$launch($directory)) {
                foreach ((array)glob($directory . DIRECTORY_SEPARATOR . '*') as $file) @unlink($file);
                @rmdir($directory); return ['code' => 503, 'msg' => 'background worker unavailable'];
            }
            return ['code' => 200, 'taskId' => $taskId];
        }
        $taskId = (string)$get($params, 'taskId', ''); $directory = $path($taskId); $statusFile = $directory . DIRECTORY_SEPARATOR . $stateName('status');
        $status = $readJson($statusFile); if (!is_array($status)) return ['code' => 404, 'msg' => 'task not found'];
        if ($action === 'query') return ['code' => 200, 'scanTaskInfo' => $status];
        if ($action === 'pause') {
            if ($status['status'] !== 'RUNNING') return ['code' => 409, 'msg' => 'task is not running'];
            $status['status'] = 'PAUSED'; $status['updatedAt'] = (int)round(microtime(true) * 1000);
            $writeJson($statusFile, $status); return ['code' => 200, 'msg' => 'paused'];
        }
        if ($action === 'resume') {
            if ($status['status'] !== 'PAUSED') return ['code' => 409, 'msg' => 'task is not paused'];
            $status['status'] = 'RUNNING'; $status['updatedAt'] = (int)round(microtime(true) * 1000);
            $writeJson($statusFile, $status); return ['code' => 200, 'msg' => 'resumed'];
        }
        if ($action === 'stop') {
            $status['status'] = 'STOPPED'; $status['finishedAt'] = (int)round(microtime(true) * 1000); $writeJson($statusFile, $status);
            return ['code' => 200, 'msg' => 'stopped'];
        }
        return ['code' => 400, 'msg' => 'unsupported scan action'];
    }
];
