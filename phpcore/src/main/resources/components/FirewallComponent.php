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
    return 'Linux';
};
$valid = static function ($value, $field, $required = false) {
    $value = trim((string)$value);
    if (($required && $value === '') || strlen($value) > 4096 || preg_match('/[\r\n\0]/', $value)) throw new InvalidArgumentException('invalid ' . $field);
    return $value;
};
$linuxTool = static function () use ($run) {
    foreach (['firewall-cmd' => 'firewalld', 'ufw' => 'ufw', 'nft' => 'nft', 'iptables' => 'iptables'] as $binary => $name) {
        $result = $run('command -v ' . $binary); if (trim($result['output']) !== '') return $name;
    }
    return 'none';
};
$parseLines = static function ($output, $tool) {
    $rules = []; $index = 0;
    foreach (preg_split('/\r?\n/', trim((string)$output)) as $line) {
        $line = trim($line); if ($line === '') continue; $index++;
        $rule = ['index' => $index, 'raw' => $line, 'tool' => $tool];
        if ($tool === 'ufw' && preg_match('/^\[\s*(\d+)\]\s+(.+)$/', $line, $m)) { $rule['index'] = (int)$m[1]; $rule['rule'] = $m[2]; }
        if ($tool === 'iptables') {
            $parts = preg_split('/\s+/', $line);
            if (count($parts) >= 10 && ctype_digit($parts[0])) {
                $rule += ['num' => (int)$parts[0], 'pkts' => $parts[1], 'bytes' => $parts[2], 'target' => $parts[3],
                    'prot' => $parts[4], 'in' => $parts[6], 'out' => $parts[7], 'source' => $parts[8], 'destination' => $parts[9]];
            }
        }
        $rules[] = $rule;
        if (count($rules) >= 5000) break;
    }
    return $rules;
};
return [
    'id' => 'FirewallComponent', 'version' => '1.0.0',
    'handle' => static function ($action, $params) use ($get, $available, $run, $osFamily, $valid, $linuxTool, $parseLines) {
        if (!$available('exec') && !$available('shell_exec')) return ['code' => 503, 'msg' => 'firewall command backend is unavailable'];
        $family = $osFamily(); $os = $family === 'Windows' ? 'windows' : ($family === 'Darwin' ? 'macos' : 'linux');
        $tool = $family === 'Windows' ? 'netsh' : ($family === 'Darwin' ? 'pf' : $linuxTool());
        if ($action === 'status') {
            if ($family === 'Windows') $result = $run('netsh advfirewall show allprofiles state');
            elseif ($family === 'Darwin') $result = $run('pfctl -s info');
            elseif ($tool === 'firewalld') $result = $run('firewall-cmd --state');
            elseif ($tool === 'ufw') $result = $run('ufw status');
            elseif ($tool === 'nft') $result = $run('nft list ruleset');
            elseif ($tool === 'iptables') $result = $run('iptables -S');
            else $result = ['output' => 'No supported firewall command was found', 'status' => null];
            $text = trim($result['output']);
            $enabled = preg_match('/\b(running|active|enabled)\b/i', $text) === 1 && preg_match('/\b(inactive|disabled|not running)\b/i', $text) !== 1;
            return ['code' => 200, 'data' => ['action' => 'status', 'os' => $os,
                'detail' => ['tool' => $tool, 'enabled' => $enabled, 'status' => $text]]];
        }
        if ($action === 'list') {
            $direction = strtolower($valid($get($params, 'direction', ''), 'direction'));
            $profile = $valid($get($params, 'profile', ''), 'profile');
            if ($family === 'Windows') $result = $run('netsh advfirewall firewall show rule name=all' . ($direction !== '' ? ' dir=' . escapeshellarg($direction) : ''));
            elseif ($family === 'Darwin') $result = $run('pfctl -sr');
            elseif ($tool === 'firewalld') $result = $run('firewall-cmd --list-all' . ($profile !== '' ? ' --zone=' . escapeshellarg($profile) : ''));
            elseif ($tool === 'ufw') $result = $run('ufw status numbered');
            elseif ($tool === 'nft') $result = $run('nft -a list ruleset');
            elseif ($tool === 'iptables') $result = $run('iptables -L -n -v --line-numbers');
            else $result = ['output' => '', 'status' => null];
            $rules = $parseLines($result['output'], $tool);
            return ['code' => 200, 'data' => ['action' => 'listRules', 'os' => $os, 'tool' => $tool,
                'total' => count($rules), 'rules' => $rules]];
        }
        if ($action === 'toggle') {
            $enable = (bool)$get($params, 'enable', false);
            if ($family === 'Windows') $cmd = 'netsh advfirewall set allprofiles state ' . ($enable ? 'on' : 'off');
            elseif ($family === 'Darwin') $cmd = 'pfctl ' . ($enable ? '-e' : '-d');
            elseif ($tool === 'firewalld') $cmd = 'systemctl ' . ($enable ? 'enable --now' : 'disable --now') . ' firewalld';
            elseif ($tool === 'ufw') $cmd = 'ufw --force ' . ($enable ? 'enable' : 'disable');
            else return ['code' => 400, 'msg' => 'toggle is not supported by ' . $tool];
            $result = $run($cmd);
            return ['code' => 200, 'data' => ['action' => 'toggle', 'os' => $os, 'tool' => $tool,
                'enabled' => $enable, 'output' => trim($result['output'])]];
        }
        if ($action === 'add') {
            $name = $valid($get($params, 'ruleName', 'LeoRule'), 'ruleName', true);
            $direction = strtolower($valid($get($params, 'direction', 'in'), 'direction'));
            $effect = strtolower($valid($get($params, 'effect', $get($params, 'ruleAction', 'allow')), 'action'));
            $protocol = strtolower($valid($get($params, 'protocol', 'tcp'), 'protocol'));
            $localPort = $valid($get($params, 'localPort', ''), 'localPort');
            $remotePort = $valid($get($params, 'remotePort', ''), 'remotePort');
            $remoteAddress = $valid($get($params, 'remoteAddress', ''), 'remoteAddress');
            $rawRule = $valid($get($params, 'rawRule', ''), 'rawRule');
            if ($rawRule !== '') $cmd = $rawRule;
            elseif ($family === 'Windows') $cmd = 'netsh advfirewall firewall add rule name=' . escapeshellarg($name)
                . ' dir=' . ($direction === 'out' ? 'out' : 'in') . ' action=' . ($effect === 'block' || $effect === 'deny' ? 'block' : 'allow')
                . ' protocol=' . escapeshellarg($protocol) . ($localPort !== '' ? ' localport=' . escapeshellarg($localPort) : '')
                . ($remotePort !== '' ? ' remoteport=' . escapeshellarg($remotePort) : '') . ($remoteAddress !== '' ? ' remoteip=' . escapeshellarg($remoteAddress) : '');
            elseif ($tool === 'firewalld') $cmd = 'firewall-cmd --permanent --add-port=' . escapeshellarg($localPort . '/' . $protocol) . ' && firewall-cmd --reload';
            elseif ($tool === 'ufw') $cmd = 'ufw ' . ($effect === 'block' || $effect === 'deny' ? 'deny' : 'allow')
                . ($remoteAddress !== '' ? ' from ' . escapeshellarg($remoteAddress) : '') . ' to any'
                . ($localPort !== '' ? ' port ' . escapeshellarg($localPort) : '') . ' proto ' . escapeshellarg($protocol);
            elseif ($tool === 'iptables') $cmd = 'iptables -A ' . ($direction === 'out' ? 'OUTPUT' : 'INPUT') . ' -p ' . escapeshellarg($protocol)
                . ($localPort !== '' ? ' --dport ' . escapeshellarg($localPort) : '') . ($remoteAddress !== '' ? ' -s ' . escapeshellarg($remoteAddress) : '')
                . ' -j ' . ($effect === 'block' || $effect === 'deny' ? 'DROP' : 'ACCEPT');
            else return ['code' => 400, 'msg' => 'add requires rawRule for ' . $tool];
            $result = $run($cmd);
            return ['code' => 200, 'data' => ['action' => 'add', 'os' => $os, 'tool' => $tool,
                'ruleName' => $name, 'output' => trim($result['output'])]];
        }
        if ($action === 'delete') {
            $name = $valid($get($params, 'ruleName', ''), 'ruleName');
            $index = $valid($get($params, 'ruleIndex', ''), 'ruleIndex');
            $rawRule = $valid($get($params, 'rawRule', ''), 'rawRule');
            if ($rawRule !== '') $cmd = $rawRule;
            elseif ($family === 'Windows' && $name !== '') $cmd = 'netsh advfirewall firewall delete rule name=' . escapeshellarg($name);
            elseif ($tool === 'ufw' && ctype_digit($index)) $cmd = 'ufw --force delete ' . (int)$index;
            elseif ($tool === 'iptables' && ctype_digit($index)) $cmd = 'iptables -D INPUT ' . (int)$index;
            else return ['code' => 400, 'msg' => 'ruleName, numeric ruleIndex, or rawRule is required'];
            $result = $run($cmd);
            return ['code' => 200, 'data' => ['action' => 'delete', 'os' => $os, 'tool' => $tool,
                'ruleName' => $name, 'ruleIndex' => $index, 'output' => trim($result['output'])]];
        }
        return ['code' => 400, 'msg' => 'unsupported firewall action'];
    }
];
