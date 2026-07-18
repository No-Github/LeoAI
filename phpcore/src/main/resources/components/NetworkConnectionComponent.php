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
        $lines = []; $status = 0; @exec($command . ' 2>&1', $lines, $status);
        return ['output' => substr(implode("\n", $lines), 0, 8 * 1024 * 1024), 'status' => $status];
    }
    if ($available('shell_exec')) {
        $output = @shell_exec($command . ' 2>&1');
        return ['output' => is_string($output) ? substr($output, 0, 8 * 1024 * 1024) : '', 'status' => null];
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
$endpoint = static function ($value) {
    $value = trim((string)$value);
    if ($value === '') return ['', ''];
    if ($value[0] === '[' && preg_match('/^\[(.*)\]:(\*|[0-9]+)$/', $value, $match)) return [$match[1], $match[2]];
    $position = strrpos($value, ':');
    if ($position === false) return [$value, ''];
    return [substr($value, 0, $position), substr($value, $position + 1)];
};
$state = static function ($value) {
    $value = strtoupper(trim((string)$value));
    $map = ['LISTENING' => 'LISTEN', 'ESTABLISHED' => 'ESTABLISHED', 'UNCONN' => '', 'CLOSE-WAIT' => 'CLOSE_WAIT'];
    return isset($map[$value]) ? $map[$value] : str_replace('-', '_', $value);
};
$windows = static function () use ($run, $endpoint, $state) {
    $names = []; $tasks = $run('tasklist /FO CSV /NH');
    foreach (preg_split('/\r?\n/', $tasks['output']) as $line) {
        $columns = str_getcsv($line);
        if (count($columns) >= 2 && is_numeric($columns[1])) $names[(string)(int)$columns[1]] = $columns[0];
    }
    $result = []; $netstat = $run('netstat -ano');
    foreach (preg_split('/\r?\n/', $netstat['output']) as $line) {
        $parts = preg_split('/\s+/', trim($line));
        if (count($parts) < 4 || !preg_match('/^(TCP|UDP)$/i', $parts[0])) continue;
        $protocol = strtoupper($parts[0]); list($localAddr, $localPort) = $endpoint($parts[1]);
        list($remoteAddr, $remotePort) = $endpoint($parts[2]);
        $stateIndex = $protocol === 'TCP' ? 3 : -1; $pidIndex = $protocol === 'TCP' ? 4 : 3;
        if (!isset($parts[$pidIndex])) continue;
        $pid = (string)(int)$parts[$pidIndex];
        $item = ['protocol' => $protocol, 'localAddr' => $localAddr, 'localPort' => $localPort,
            'remoteAddr' => $remoteAddr, 'remotePort' => $remotePort,
            'state' => $stateIndex >= 0 ? $state($parts[$stateIndex]) : '', 'pid' => $pid];
        if (isset($names[$pid])) $item['process'] = $names[$pid];
        $result[] = $item;
    }
    return [$result, ['source=netstat -ano']];
};
$linuxCommand = static function () use ($run, $endpoint, $state) {
    $result = []; $diagnostics = []; $raw = $run('ss -tunap');
    if (stripos($raw['output'], 'Netid') === false) {
        $raw = $run('netstat -tunap'); $diagnostics[] = 'source=netstat -tunap';
        foreach (preg_split('/\r?\n/', $raw['output']) as $line) {
            $parts = preg_split('/\s+/', trim($line));
            if (count($parts) < 6 || !preg_match('/^(tcp|udp)/i', $parts[0])) continue;
            $protocol = stripos($parts[0], 'udp') === 0 ? 'UDP' : 'TCP';
            list($localAddr, $localPort) = $endpoint($parts[3]); list($remoteAddr, $remotePort) = $endpoint($parts[4]);
            $stateIndex = $protocol === 'TCP' ? 5 : -1; $ownerIndex = $protocol === 'TCP' ? 6 : 5;
            $item = ['protocol' => $protocol, 'state' => $stateIndex >= 0 && isset($parts[$stateIndex]) ? $state($parts[$stateIndex]) : '',
                'localAddr' => $localAddr, 'localPort' => $localPort, 'remoteAddr' => $remoteAddr, 'remotePort' => $remotePort];
            if (isset($parts[$ownerIndex]) && preg_match('/^(\d+)\/(.+)$/', $parts[$ownerIndex], $match)) { $item['pid'] = $match[1]; $item['process'] = $match[2]; }
            $result[] = $item;
        }
        return [$result, $diagnostics];
    }
    $diagnostics[] = 'source=ss -tunap';
    foreach (preg_split('/\r?\n/', $raw['output']) as $line) {
        $line = trim($line);
        if ($line === '' || stripos($line, 'Netid') === 0) continue;
        $parts = preg_split('/\s+/', $line, 7);
        if (count($parts) < 6) continue;
        $protocol = stripos($parts[0], 'udp') === 0 ? 'UDP' : 'TCP';
        list($localAddr, $localPort) = $endpoint($parts[4]); list($remoteAddr, $remotePort) = $endpoint($parts[5]);
        $item = ['protocol' => $protocol, 'state' => $state($parts[1]),
            'localAddr' => $localAddr, 'localPort' => $localPort,
            'remoteAddr' => $remoteAddr, 'remotePort' => $remotePort];
        $owner = isset($parts[6]) ? $parts[6] : '';
        if (preg_match('/pid=(\d+)/', $owner, $match)) $item['pid'] = $match[1];
        if (preg_match('/\(\("([^"]+)"/', $owner, $match)) $item['process'] = $match[1];
        $result[] = $item;
    }
    return [$result, $diagnostics];
};
$procAddress = static function ($value) {
    $value = strtoupper(trim((string)$value));
    if (strlen($value) === 8) {
        $packed = @pack('H*', $value); $decoded = is_string($packed) && function_exists('inet_ntop') ? @inet_ntop(strrev($packed)) : false;
        return is_string($decoded) ? $decoded : $value;
    }
    if (strlen($value) === 32) {
        $packed = '';
        foreach (str_split($value, 8) as $group) $packed .= strrev(pack('H*', $group));
        $decoded = function_exists('inet_ntop') ? @inet_ntop($packed) : false;
        return is_string($decoded) ? $decoded : $value;
    }
    return $value;
};
$procOwners = static function ($wanted) {
    if (!$wanted) return []; $owners = []; $checked = 0;
    foreach ((array)glob('/proc/[0-9]*', GLOB_ONLYDIR) as $directory) {
        $pid = (int)basename($directory); if ($pid <= 0) continue;
        $name = null;
        foreach ((array)glob($directory . '/fd/*') as $fd) {
            if (++$checked > 200000) break 2;
            $target = @readlink($fd);
            if (!is_string($target) || !preg_match('/^socket:\[(\d+)\]$/', $target, $match) || !isset($wanted[$match[1]])) continue;
            if ($name === null) $name = trim((string)@file_get_contents($directory . '/comm'));
            $owners[$match[1]] = ['pid' => (string)$pid, 'process' => $name];
        }
    }
    return $owners;
};
$linuxProc = static function () use ($procAddress, $procOwners) {
    $connections = []; $wanted = []; $readable = false;
    $states = ['01' => 'ESTABLISHED', '02' => 'SYN_SENT', '03' => 'SYN_RECV', '04' => 'FIN_WAIT1',
        '05' => 'FIN_WAIT2', '06' => 'TIME_WAIT', '07' => 'CLOSE', '08' => 'CLOSE_WAIT',
        '09' => 'LAST_ACK', '0A' => 'LISTEN', '0B' => 'CLOSING'];
    foreach (['TCP' => ['/proc/net/tcp', '/proc/net/tcp6'], 'UDP' => ['/proc/net/udp', '/proc/net/udp6']] as $protocol => $tables) {
        foreach ($tables as $table) {
            if (!is_readable($table)) continue; $readable = true;
            foreach (array_slice((array)@file($table, FILE_IGNORE_NEW_LINES), 1) as $line) {
                $parts = preg_split('/\s+/', trim($line)); if (count($parts) < 10) continue;
                $local = explode(':', $parts[1]); $remote = explode(':', $parts[2]);
                if (count($local) !== 2 || count($remote) !== 2) continue;
                $inode = ctype_digit((string)$parts[9]) ? (string)$parts[9] : '';
                $connectionState = $protocol === 'UDP' && strtoupper($parts[3]) === '07' ? ''
                    : (isset($states[strtoupper($parts[3])]) ? $states[strtoupper($parts[3])] : '');
                $item = ['protocol' => $protocol, 'state' => $connectionState,
                    'localAddr' => $procAddress($local[0]), 'localPort' => (string)hexdec($local[1]),
                    'remoteAddr' => $procAddress($remote[0]), 'remotePort' => (string)hexdec($remote[1]),
                    '_inode' => $inode];
                if ($inode !== '') $wanted[$inode] = true;
                $connections[] = $item; if (count($connections) >= 5000) break 3;
            }
        }
    }
    if (!$readable) return null;
    $owners = $procOwners($wanted);
    foreach ($connections as &$item) {
        $inode = $item['_inode']; unset($item['_inode']);
        if ($inode !== '' && isset($owners[$inode])) $item += $owners[$inode];
    }
    unset($item);
    return [$connections, ['source=/proc/net']];
};
$linux = static function () use ($linuxProc, $linuxCommand) {
    $native = $linuxProc(); return is_array($native) ? $native : $linuxCommand();
};
$mac = static function () use ($run, $endpoint, $state) {
    $result = []; $raw = $run('lsof -nP -iTCP -iUDP');
    foreach (preg_split('/\r?\n/', $raw['output']) as $line) {
        if (stripos($line, 'COMMAND') === 0 || trim($line) === '') continue;
        $parts = preg_split('/\s+/', trim($line), 9);
        if (count($parts) < 9) continue;
        $name = $parts[8]; $connectionState = '';
        if (preg_match('/\s+\(([^)]+)\)$/', $name, $match)) { $connectionState = $state($match[1]); $name = preg_replace('/\s+\([^)]+\)$/', '', $name); }
        $sides = preg_split('/->/', $name, 2); list($localAddr, $localPort) = $endpoint($sides[0]);
        list($remoteAddr, $remotePort) = isset($sides[1]) ? $endpoint($sides[1]) : ['*', '*'];
        $result[] = ['protocol' => stripos($parts[7], 'UDP') !== false ? 'UDP' : 'TCP',
            'state' => $connectionState, 'localAddr' => $localAddr, 'localPort' => $localPort,
            'remoteAddr' => $remoteAddr, 'remotePort' => $remotePort,
            'pid' => $parts[1], 'process' => $parts[0], 'user' => $parts[2]];
    }
    return [$result, ['source=lsof']];
};
$collect = static function ($family) use ($windows, $linux, $mac) {
    if ($family === 'Windows') return $windows();
    if ($family === 'Darwin') return $mac();
    return $linux();
};
$top = static function ($counts, $limit) {
    arsort($counts); $result = [];
    foreach (array_slice($counts, 0, $limit, true) as $key => $count) $result[] = ['key' => $key, 'count' => $count];
    return $result;
};
return [
    'id' => 'NetworkConnectionComponent', 'version' => '1.0.0',
    'handle' => static function ($action, $params) use ($get, $available, $osFamily, $collect, $top) {
        if ($action !== 'list' && $action !== 'summary') return ['code' => 400, 'msg' => 'unsupported network connection action'];
        $family = $osFamily();
        if ($family !== 'Linux' && !$available('exec') && !$available('shell_exec')) return ['code' => 503, 'msg' => 'network command backend is unavailable'];
        list($connections, $diagnostics) = $collect($family);
        $os = $family === 'Windows' ? 'windows' : ($family === 'Darwin' ? 'macos' : 'linux');
        if ($action === 'summary') {
            $byState = []; $byProtocol = []; $byProcess = []; $byRemoteIp = []; $listening = [];
            foreach ($connections as $item) {
                foreach ([['state', &$byState, 'UNKNOWN'], ['protocol', &$byProtocol, 'UNKNOWN'], ['process', &$byProcess, 'unknown']] as &$counter) {
                    $key = !empty($item[$counter[0]]) ? (string)$item[$counter[0]] : $counter[2];
                    $counter[1][$key] = isset($counter[1][$key]) ? $counter[1][$key] + 1 : 1;
                }
                unset($counter);
                $remote = isset($item['remoteAddr']) ? (string)$item['remoteAddr'] : '';
                if ($remote !== '' && !in_array($remote, ['*', '0.0.0.0', '::', '127.0.0.1', '::1'], true)) $byRemoteIp[$remote] = isset($byRemoteIp[$remote]) ? $byRemoteIp[$remote] + 1 : 1;
                if (isset($item['state']) && $item['state'] === 'LISTEN') $listening[] = ['port' => $item['localPort'],
                    'protocol' => $item['protocol'], 'process' => isset($item['process']) ? $item['process'] : (isset($item['pid']) ? 'pid:' . $item['pid'] : 'unknown'),
                    'localAddr' => $item['localAddr']];
            }
            return ['code' => 200, 'action' => 'summary', 'os' => $os, 'totalConnections' => count($connections),
                'byState' => $byState, 'byProtocol' => $byProtocol, 'byProcess' => $top($byProcess, 30),
                'byRemoteIp' => $top($byRemoteIp, 30), 'listeningPorts' => $listening, 'diagnostics' => $diagnostics];
        }
        $filtered = []; $max = max(1, min(5000, (int)$get($params, 'maxEntries', 2000)));
        foreach ($connections as $item) {
            if ((bool)$get($params, 'listeningOnly', false) && (!isset($item['state']) || $item['state'] !== 'LISTEN')) continue;
            $checks = ['state' => 'state', 'protocol' => 'protocol', 'pid' => 'pid']; $matched = true;
            foreach ($checks as $parameter => $field) {
                $wanted = trim((string)$get($params, $parameter, ''));
                if ($wanted !== '' && stripos(isset($item[$field]) ? (string)$item[$field] : '', $wanted) === false) { $matched = false; break; }
            }
            $port = trim((string)$get($params, 'port', ''));
            if ($matched && $port !== '' && $port !== (string)$item['localPort'] && $port !== (string)$item['remotePort']) $matched = false;
            $process = trim((string)$get($params, 'process', ''));
            if ($matched && $process !== '' && stripos(isset($item['process']) ? (string)$item['process'] : '', $process) === false) $matched = false;
            $remote = trim((string)$get($params, 'remoteIp', ''));
            if ($matched && $remote !== '' && strpos((string)$item['remoteAddr'], $remote) !== 0) $matched = false;
            if ($matched) $filtered[] = $item;
            if (count($filtered) >= $max) break;
        }
        return ['code' => 200, 'action' => 'list', 'os' => $os, 'total' => count($connections),
            'filtered' => count($filtered), 'connections' => $filtered, 'diagnostics' => $diagnostics];
    }
];
