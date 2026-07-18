<?php
$get = static function ($value, $key, $default = null) {
    return is_array($value) && array_key_exists($key, $value) ? $value[$key] : $default;
};
$available = static function ($name) {
    return function_exists($name) && !in_array($name,
        array_map('trim', explode(',', (string)ini_get('disable_functions'))), true);
};
$run = static function ($command) use ($available) {
    if ($available('exec')) {
        $lines = []; $status = 0;
        @exec($command . ' 2>&1', $lines, $status);
        return ['output' => implode("\n", $lines), 'status' => $status];
    }
    if ($available('shell_exec')) {
        $output = @shell_exec($command . ' 2>&1');
        return ['output' => is_string($output) ? $output : '', 'status' => null];
    }
    return ['output' => '', 'status' => null];
};
$osFamily = static function () {
    if (defined('PHP_OS_FAMILY')) return constant('PHP_OS_FAMILY');
    $name = strtoupper(PHP_OS);
    if (strpos($name, 'WIN') === 0) return 'Windows';
    if (strpos($name, 'DAR') === 0) return 'Darwin';
    if (strpos($name, 'LIN') === 0) return 'Linux';
    return PHP_OS;
};
$processName = static function ($command) {
    $command = trim((string)$command);
    if ($command === '') return '';
    $parts = preg_split('/\s+/', $command);
    return basename($parts[0]);
};
$listUnix = static function () use ($run, $processName) {
    $result = $run('ps -eo pid=,ppid=,user=,rss=,comm=,args='); $processes = [];
    foreach (preg_split('/\r?\n/', trim($result['output'])) as $line) {
        $parts = preg_split('/\s+/', trim($line), 6);
        if (count($parts) < 5 || !preg_match('/^[0-9]+$/', $parts[0])) continue;
        $cmd = isset($parts[5]) ? $parts[5] : $parts[4];
        $processes[] = ['pid' => (int)$parts[0], 'ppid' => (int)$parts[1], 'user' => $parts[2],
            'memKb' => is_numeric($parts[3]) ? (int)$parts[3] : 0,
            'name' => $parts[4] !== '' ? basename($parts[4]) : $processName($cmd), 'cmd' => $cmd];
        if (count($processes) >= 2000) break;
    }
    return $processes;
};
$listLinuxProc = static function () use ($processName) {
    $processes = []; $directories = (array)glob('/proc/[0-9]*', GLOB_ONLYDIR);
    foreach ($directories as $directory) {
        $pid = (int)basename($directory); if ($pid <= 0) continue;
        $stat = @file_get_contents($directory . '/stat');
        if (!is_string($stat)) continue;
        $open = strpos($stat, '('); $close = strrpos($stat, ')'); if ($open === false || $close === false || $close <= $open) continue;
        $name = trim(substr($stat, $open + 1, $close - $open - 1));
        $fields = preg_split('/\s+/', trim(substr($stat, $close + 1)));
        $ppid = isset($fields[1]) && is_numeric($fields[1]) ? (int)$fields[1] : 0;
        $cmdline = @file_get_contents($directory . '/cmdline');
        $cmd = is_string($cmdline) ? trim(str_replace("\0", ' ', $cmdline)) : '';
        if ($cmd === '') $cmd = $name;
        $uid = null; $memKb = 0; $status = @file($directory . '/status', FILE_IGNORE_NEW_LINES);
        foreach ((array)$status as $line) {
            if (strpos($line, 'Uid:') === 0 && preg_match('/^Uid:\s+(\d+)/', $line, $match)) $uid = (int)$match[1];
            elseif (strpos($line, 'VmRSS:') === 0 && preg_match('/^VmRSS:\s+(\d+)/', $line, $match)) $memKb = (int)$match[1];
        }
        $user = $uid === null ? '' : (string)$uid;
        if ($uid !== null && function_exists('posix_getpwuid')) {
            $record = @posix_getpwuid($uid); if (is_array($record) && isset($record['name'])) $user = (string)$record['name'];
        }
        $processes[] = ['pid' => $pid, 'ppid' => $ppid, 'user' => $user, 'memKb' => $memKb,
            'name' => $name !== '' ? $name : $processName($cmd), 'cmd' => $cmd];
        if (count($processes) >= 2000) break;
    }
    return $processes;
};
$listWindows = static function () use ($run, $processName) {
    $result = $run('wmic process get ProcessId,ParentProcessId,Name,CommandLine,WorkingSetSize /format:csv');
    $processes = [];
    foreach (preg_split('/\r?\n/', trim($result['output'])) as $line) {
        if (trim($line) === '') continue;
        $columns = str_getcsv($line);
        if (count($columns) < 6 || !is_numeric($columns[count($columns) - 2])) continue;
        $cmd = (string)$columns[1]; $name = (string)$columns[2];
        $processes[] = ['pid' => (int)$columns[count($columns) - 2],
            'ppid' => is_numeric($columns[count($columns) - 3]) ? (int)$columns[count($columns) - 3] : 0,
            'memKb' => is_numeric($columns[count($columns) - 1]) ? (int)round($columns[count($columns) - 1] / 1024) : 0,
            'name' => $name !== '' ? $name : $processName($cmd), 'cmd' => $cmd !== '' ? $cmd : $name];
        if (count($processes) >= 2000) break;
    }
    if ($processes) return $processes;
    $result = $run('tasklist /fo csv /nh');
    foreach (preg_split('/\r?\n/', trim($result['output'])) as $line) {
        $columns = str_getcsv($line);
        if (count($columns) < 5 || !is_numeric($columns[1])) continue;
        $memory = (int)preg_replace('/[^0-9]/', '', $columns[4]);
        $processes[] = ['pid' => (int)$columns[1], 'name' => $columns[0], 'cmd' => $columns[0], 'memKb' => $memory];
    }
    return array_slice($processes, 0, 2000);
};
$linuxPortPids = static function ($port) {
    $inodes = [];
    foreach (['/proc/net/tcp', '/proc/net/tcp6', '/proc/net/udp', '/proc/net/udp6'] as $table) {
        foreach (array_slice((array)@file($table, FILE_IGNORE_NEW_LINES), 1) as $line) {
            $parts = preg_split('/\s+/', trim($line)); if (count($parts) < 10) continue;
            $local = explode(':', $parts[1]); if (count($local) !== 2 || hexdec($local[1]) !== (int)$port) continue;
            if (ctype_digit((string)$parts[9])) $inodes[(string)$parts[9]] = true;
        }
    }
    if (!$inodes) return [];
    $pids = []; $checked = 0;
    foreach ((array)glob('/proc/[0-9]*', GLOB_ONLYDIR) as $directory) {
        $pid = (int)basename($directory); if ($pid <= 0) continue;
        foreach ((array)glob($directory . '/fd/*') as $fd) {
            if (++$checked > 200000) break 2;
            $target = @readlink($fd);
            if (is_string($target) && preg_match('/^socket:\[(\d+)\]$/', $target, $match) && isset($inodes[$match[1]])) {
                $pids[$pid] = true; break;
            }
        }
    }
    return $pids;
};
$portPids = static function ($family, $port) use ($run, $linuxPortPids) {
    $pids = [];
    if ($family === 'Windows') {
        $result = $run('netstat -ano -p tcp');
        foreach (preg_split('/\r?\n/', $result['output']) as $line) {
            if (preg_match('/^\s*TCP\s+\S+:' . (int)$port . '\s+\S+\s+LISTENING\s+(\d+)/i', $line, $match)) $pids[(int)$match[1]] = true;
        }
    } elseif ($family === 'Darwin') {
        $result = $run('lsof -nP -iTCP:' . (int)$port . ' -sTCP:LISTEN -Fp');
        foreach (preg_split('/\r?\n/', $result['output']) as $line) if (preg_match('/^p(\d+)$/', trim($line), $match)) $pids[(int)$match[1]] = true;
    } else {
        $pids = $linuxPortPids($port);
    }
    return $pids;
};
return [
    'id' => 'ProcessComponent', 'version' => '1.0.0',
    'handle' => static function ($action, $params) use ($get, $available, $run, $osFamily, $listUnix, $listLinuxProc, $listWindows, $portPids) {
        $family = $osFamily();
        if ($action === 'kill') {
            $pid = (int)$get($params, 'pid', -1); $force = (bool)$get($params, 'force', false);
            if ($pid <= 0) return ['code' => 400, 'msg' => 'pid is required for kill action'];
            if ($family !== 'Windows' && function_exists('posix_kill')) {
                $ok = @posix_kill($pid, $force ? 9 : 15);
                return $ok ? ['code' => 200, 'action' => 'kill', 'pid' => $pid, 'force' => $force, 'output' => '']
                    : ['code' => 500, 'msg' => 'process signal failed', 'pid' => $pid, 'force' => $force];
            }
            if (!$available('exec') && !$available('shell_exec')) return ['code' => 503, 'msg' => 'process command backend is unavailable'];
            $command = $family === 'Windows' ? 'taskkill /PID ' . $pid . ($force ? ' /F' : '')
                : 'kill ' . ($force ? '-9 ' : '-15 ') . $pid;
            $result = $run($command);
            if ($result['status'] !== null && (int)$result['status'] !== 0) {
                return ['code' => 500, 'msg' => trim($result['output']), 'pid' => $pid, 'force' => $force];
            }
            return ['code' => 200, 'action' => 'kill', 'pid' => $pid, 'force' => $force, 'output' => trim($result['output'])];
        }
        if ($action !== 'list' && $action !== 'find') return ['code' => 400, 'msg' => 'unsupported process action'];
        if ($family !== 'Linux' && !$available('exec') && !$available('shell_exec')) return ['code' => 503, 'msg' => 'process command backend is unavailable'];
        if ($family === 'Windows') $processes = $listWindows();
        elseif ($family === 'Linux') { $processes = $listLinuxProc(); if (!$processes) $processes = $listUnix(); }
        else $processes = $listUnix();
        if (!$processes && !$available('exec') && !$available('shell_exec')) return ['code' => 503, 'msg' => 'process data source is unavailable'];
        if ($action === 'find') {
            $name = trim((string)$get($params, 'name', '')); $pid = (int)$get($params, 'pid', -1); $port = (int)$get($params, 'port', -1);
            $pids = $port > 0 ? $portPids($family, $port) : [];
            $filtered = [];
            foreach ($processes as $process) {
                if ($pid >= 0 && (int)$process['pid'] !== $pid) continue;
                if ($name !== '' && stripos((string)$process['name'] . ' ' . (string)$process['cmd'], $name) === false) continue;
                if ($port > 0 && !isset($pids[(int)$process['pid']])) continue;
                if ($port > 0) $process['matchedPort'] = $port;
                $filtered[] = $process;
            }
            return ['code' => 200, 'action' => 'find', 'total' => count($filtered), 'processes' => $filtered];
        }
        return ['code' => 200, 'action' => 'list', 'total' => count($processes), 'processes' => $processes,
            'os' => $family === 'Darwin' ? 'macOS' : $family];
    }
];
