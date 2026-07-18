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
        return ['output' => substr(implode("\n", $lines), 0, 4 * 1024 * 1024), 'status' => $status];
    }
    if ($available('shell_exec')) {
        $output = @shell_exec($command . ' 2>&1');
        return ['output' => is_string($output) ? substr($output, 0, 4 * 1024 * 1024) : '', 'status' => null];
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
$validName = static function ($name) {
    $name = trim((string)$name);
    if ($name === '' || strlen($name) > 240 || preg_match('/[\r\n\0]/', $name)) throw new InvalidArgumentException('invalid serviceName');
    return $name;
};
$list = static function ($family) use ($run) {
    $services = []; $diagnostics = [];
    if ($family === 'Windows') {
        $raw = $run('sc queryex type= service state= all'); $current = null;
        foreach (preg_split('/\r?\n/', $raw['output']) as $line) {
            if (preg_match('/^SERVICE_NAME:\s*(.+)$/i', trim($line), $match)) {
                if (is_array($current)) $services[] = $current;
                $current = ['serviceName' => trim($match[1])];
            } elseif (is_array($current) && preg_match('/^DISPLAY_NAME:\s*(.+)$/i', trim($line), $match)) $current['displayName'] = trim($match[1]);
            elseif (is_array($current) && preg_match('/^STATE\s*:\s*\d+\s+(\S+)/i', trim($line), $match)) $current['status'] = strtoupper($match[1]);
            elseif (is_array($current) && preg_match('/^PID\s*:\s*(\d+)/i', trim($line), $match)) $current['pid'] = (int)$match[1];
        }
        if (is_array($current)) $services[] = $current;
        $diagnostics[] = 'source=sc queryex';
    } elseif ($family === 'Darwin') {
        $raw = $run('launchctl list');
        foreach (array_slice(preg_split('/\r?\n/', trim($raw['output'])), 1) as $line) {
            $parts = preg_split('/\s+/', trim($line), 3);
            if (count($parts) !== 3) continue;
            $services[] = ['serviceName' => $parts[2], 'type' => 'launchd', 'pid' => $parts[0] === '-' ? null : (int)$parts[0],
                'lastExitStatus' => $parts[1], 'status' => $parts[0] === '-' ? ((int)$parts[1] === 0 ? 'STOPPED' : 'FAILED') : 'RUNNING'];
        }
        $diagnostics[] = 'source=launchctl list';
    } else {
        $raw = $run('systemctl list-units --type=service --all --no-legend --no-pager');
        foreach (preg_split('/\r?\n/', trim($raw['output'])) as $line) {
            $parts = preg_split('/\s+/', trim($line), 5);
            if (count($parts) < 4 || substr($parts[0], -8) !== '.service') continue;
            $services[] = ['serviceName' => $parts[0], 'load' => $parts[1], 'active' => $parts[2], 'sub' => $parts[3],
                'status' => strtoupper($parts[2]), 'description' => isset($parts[4]) ? $parts[4] : '', 'type' => 'systemd'];
        }
        if (!$services) {
            $raw = $run('service --status-all');
            foreach (preg_split('/\r?\n/', trim($raw['output'])) as $line) if (preg_match('/\[\s*([+?-])\s*\]\s*(\S+)/', $line, $match)) {
                $services[] = ['serviceName' => $match[2], 'status' => $match[1] === '+' ? 'RUNNING' : 'STOPPED', 'type' => 'sysv'];
            }
            $diagnostics[] = 'source=service --status-all';
        } else $diagnostics[] = 'source=systemctl';
    }
    return [$services, $diagnostics];
};
$command = static function ($family, $operation, $name) {
    $arg = escapeshellarg($name);
    if ($family === 'Windows') {
        $map = ['start' => 'start', 'stop' => 'stop', 'restart' => 'stop', 'enable' => 'config', 'disable' => 'config', 'delete' => 'delete', 'query' => 'qc'];
        $cmd = 'sc ' . $map[$operation] . ' ' . $arg;
        if ($operation === 'restart') $cmd .= ' && sc start ' . $arg;
        if ($operation === 'enable') $cmd .= ' start= auto';
        if ($operation === 'disable') $cmd .= ' start= disabled';
        return $cmd;
    }
    if ($family === 'Darwin') {
        if ($operation === 'start') return 'launchctl kickstart -k system/' . $arg;
        if ($operation === 'stop') return 'launchctl kill SIGTERM system/' . $arg;
        if ($operation === 'restart') return 'launchctl kickstart -k system/' . $arg;
        if ($operation === 'enable') return 'launchctl enable system/' . $arg;
        if ($operation === 'disable') return 'launchctl disable system/' . $arg;
        if ($operation === 'query') return 'launchctl print system/' . $arg;
        return 'launchctl bootout system ' . escapeshellarg('/Library/LaunchDaemons/' . $name . '.plist');
    }
    if ($operation === 'query') return 'systemctl status --no-pager ' . $arg . '; systemctl show --no-pager ' . $arg;
    if ($operation === 'delete') return 'systemctl disable --now ' . $arg . '; rm -f ' . escapeshellarg('/etc/systemd/system/' . $name . '.service') . '; systemctl daemon-reload';
    return 'systemctl ' . $operation . ' ' . $arg;
};
return [
    'id' => 'ServiceComponent', 'version' => '1.0.0',
    'handle' => static function ($action, $params) use ($get, $available, $run, $osFamily, $validName, $list, $command) {
        if (!$available('exec') && !$available('shell_exec')) return ['code' => 503, 'msg' => 'service command backend is unavailable'];
        $family = $osFamily(); $os = $family === 'Windows' ? 'windows' : ($family === 'Darwin' ? 'macos' : 'linux');
        if ($action === 'list') {
            list($services, $diagnostics) = $list($family);
            return ['code' => 200, 'data' => ['action' => 'list', 'os' => $os, 'total' => count($services),
                'services' => $services, 'diagnostics' => $diagnostics]];
        }
        $name = $validName($get($params, 'serviceName', ''));
        if ($action === 'create') {
            $binPath = trim((string)$get($params, 'binPath', ''));
            if ($binPath === '' || preg_match('/[\r\n\0]/', $binPath)) return ['code' => 400, 'msg' => 'invalid binPath'];
            if ($family === 'Windows') {
                $cmd = 'sc create ' . escapeshellarg($name) . ' binPath= ' . escapeshellarg($binPath);
                $display = trim((string)$get($params, 'displayName', '')); $startType = trim((string)$get($params, 'startType', 'demand'));
                if ($display !== '') $cmd .= ' DisplayName= ' . escapeshellarg($display);
                if (in_array($startType, ['auto', 'demand', 'disabled'], true)) $cmd .= ' start= ' . $startType;
                $result = $run($cmd); $data = ['action' => 'create', 'serviceName' => $name, 'binPath' => $binPath, 'os' => $os, 'output' => trim($result['output'])];
            } elseif ($family === 'Darwin') {
                $template = '<?xml version="1.0" encoding="UTF-8"?><plist version="1.0"><dict><key>Label</key><string>'
                    . htmlspecialchars($name, ENT_QUOTES, 'UTF-8') . '</string><key>ProgramArguments</key><array><string>'
                    . htmlspecialchars($binPath, ENT_QUOTES, 'UTF-8') . '</string></array><key>RunAtLoad</key><true/></dict></plist>';
                $data = ['action' => 'create', 'serviceName' => $name, 'binPath' => $binPath, 'os' => $os,
                    'msg' => 'write the launchd plist and load it', 'plistTemplate' => $template,
                    'plistPath' => '/Library/LaunchDaemons/' . $name . '.plist'];
            } else {
                $description = str_replace(["\r", "\n"], ' ', (string)($get($params, 'displayName', '') ?: $name));
                $template = "[Unit]\nDescription=" . $description . "\n[Service]\nExecStart=" . $binPath . "\nRestart=on-failure\n[Install]\nWantedBy=multi-user.target\n";
                $data = ['action' => 'create', 'serviceName' => $name, 'binPath' => $binPath, 'os' => $os,
                    'msg' => 'write the systemd unit and reload systemd', 'unitTemplate' => $template,
                    'unitPath' => '/etc/systemd/system/' . $name . '.service'];
            }
            return ['code' => 200, 'data' => $data];
        }
        if (!in_array($action, ['query', 'start', 'stop', 'restart', 'enable', 'disable', 'delete'], true)) return ['code' => 400, 'msg' => 'unsupported service action'];
        $result = $run($command($family, $action, $name));
        if ($action === 'query') return ['code' => 200, 'data' => ['action' => 'query', 'serviceName' => $name,
            'detail' => [], 'rawOutput' => substr($result['output'], 0, 8192), 'os' => $os]];
        return ['code' => 200, 'data' => ['action' => $action, 'serviceName' => $name, 'os' => $os, 'output' => trim($result['output'])]];
    }
];
