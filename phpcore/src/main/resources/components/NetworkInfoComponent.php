<?php
$get = static function ($value, $key, $default = null) {
    return is_array($value) && array_key_exists($key, $value) ? $value[$key] : $default;
};
$available = static function ($name) {
    return function_exists($name) && !in_array($name,
        array_map('trim', explode(',', (string)ini_get('disable_functions'))), true);
};
$run = static function ($command) use ($available) {
    if ($available('shell_exec')) {
        $output = @shell_exec($command . ' 2>&1');
        if (is_string($output)) return trim(substr($output, 0, 16384));
    }
    if ($available('exec')) {
        $lines = []; @exec($command . ' 2>&1', $lines);
        return trim(substr(implode("\n", $lines), 0, 16384));
    }
    return '';
};
$osFamily = static function () {
    if (defined('PHP_OS_FAMILY')) return constant('PHP_OS_FAMILY');
    $name = strtoupper(PHP_OS);
    if (strpos($name, 'WIN') === 0) return 'Windows';
    if (strpos($name, 'DAR') === 0) return 'Darwin';
    if (strpos($name, 'LIN') === 0) return 'Linux';
    return PHP_OS;
};
$interfaceTemplate = static function ($name) {
    return ['name' => $name, 'displayName' => $name, 'up' => false,
        'loopback' => $name === 'lo' || strpos($name, 'lo') === 0,
        'virtual' => preg_match('/^(docker|veth|virbr|br|vmnet|utun|tun|tap)/i', $name) === 1,
        'mtu' => 0, 'addresses' => []];
};
$interfaces = static function ($family) use ($available, $run, $interfaceTemplate) {
    $result = [];
    if ($available('net_get_interfaces')) {
        foreach ((array)@net_get_interfaces() as $name => $details) {
            $item = $interfaceTemplate((string)$name);
            $item['up'] = isset($details['up']) ? (bool)$details['up'] : true;
            foreach (isset($details['unicast']) ? (array)$details['unicast'] : [] as $address) {
                $value = isset($address['address']) ? (string)$address['address'] : '';
                if (filter_var($value, FILTER_VALIDATE_IP)) {
                    $entry = ['address' => $value, 'type' => strpos($value, ':') === false ? 'IPv4' : 'IPv6'];
                    if (!empty($address['netmask'])) $entry['netmask'] = (string)$address['netmask'];
                    $item['addresses'][] = $entry;
                } elseif (preg_match('/^[0-9a-f]{2}(?::[0-9a-f]{2}){5}$/i', $value)) {
                    $item['mac'] = strtoupper($value);
                }
            }
            $result[$name] = $item;
        }
    } elseif ($family !== 'Windows') {
        $raw = $run('ifconfig -a');
        foreach (preg_split('/\r?\n(?=\S)/', $raw) as $block) {
            if (!preg_match('/^([^\s:]+)(?::|\s)/', $block, $match)) continue;
            $name = $match[1]; $item = $interfaceTemplate($name);
            $item['up'] = preg_match('/<[^>]*\bUP\b[^>]*>|status:\s*active/i', $block) === 1;
            if (preg_match('/\bmtu\s+([0-9]+)/i', $block, $mtu)) $item['mtu'] = (int)$mtu[1];
            if (preg_match('/\b(?:ether|HWaddr)\s+([0-9a-f:]{17})/i', $block, $mac)) $item['mac'] = strtoupper($mac[1]);
            preg_match_all('/\binet6?\s+(?:addr:)?([0-9a-f:.]+)/i', $block, $ips);
            foreach (isset($ips[1]) ? $ips[1] : [] as $ip) $item['addresses'][] = ['address' => $ip, 'type' => strpos($ip, ':') === false ? 'IPv4' : 'IPv6'];
            $result[$name] = $item;
        }
    }
    if (!$result) {
        $host = function_exists('gethostname') ? @gethostname() : php_uname('n');
        $item = $interfaceTemplate($host ? $host : 'host'); $item['up'] = true;
        foreach ((array)@gethostbynamel($host) as $ip) $item['addresses'][] = ['address' => $ip, 'type' => 'IPv4'];
        $result[$item['name']] = $item;
    }
    return array_values($result);
};
$arp = static function ($family) use ($run) {
    if ($family === 'Linux' && is_readable('/proc/net/arp')) {
        $entries = []; $lines = (array)@file('/proc/net/arp', FILE_IGNORE_NEW_LINES);
        foreach (array_slice($lines, 1) as $line) {
            $parts = preg_split('/\s+/', trim($line));
            if (count($parts) < 6) continue;
            $entries[] = ['ip' => $parts[0], 'hwType' => $parts[1], 'flags' => $parts[2],
                'mac' => $parts[3], 'mask' => $parts[4], 'device' => $parts[5]];
        }
        return ['source' => '/proc/net/arp', 'entries' => $entries];
    }
    return ['source' => 'arp -a', 'raw' => substr($run('arp -a'), 0, 8192)];
};
$hexIp = static function ($value) {
    if (!preg_match('/^[0-9A-Fa-f]{8}$/', $value)) return $value;
    $bytes = array_reverse(str_split($value, 2));
    return implode('.', array_map('hexdec', $bytes));
};
$routes = static function ($family) use ($run, $hexIp) {
    if ($family === 'Linux' && is_readable('/proc/net/route')) {
        $entries = []; $lines = (array)@file('/proc/net/route', FILE_IGNORE_NEW_LINES);
        foreach (array_slice($lines, 1) as $line) {
            $parts = preg_split('/\s+/', trim($line));
            if (count($parts) < 8) continue;
            $entries[] = ['iface' => $parts[0], 'destination' => $hexIp($parts[1]),
                'gateway' => $hexIp($parts[2]), 'flags' => $parts[3], 'metric' => $parts[6], 'mask' => $hexIp($parts[7])];
        }
        return ['source' => '/proc/net/route', 'entries' => $entries];
    }
    $command = $family === 'Windows' ? 'route print' : 'netstat -rn';
    return ['source' => $command, 'raw' => substr($run($command), 0, 8192)];
};
$dns = static function ($family) use ($run) {
    if ($family !== 'Windows' && is_readable('/etc/resolv.conf')) {
        $raw = (string)@file_get_contents('/etc/resolv.conf'); $servers = [];
        foreach (preg_split('/\r?\n/', $raw) as $line) if (preg_match('/^\s*nameserver\s+(\S+)/i', $line, $match)) $servers[] = $match[1];
        return ['source' => '/etc/resolv.conf', 'nameservers' => array_values(array_unique($servers)), 'raw' => substr($raw, 0, 4096)];
    }
    $raw = $run('ipconfig /all'); $servers = [];
    preg_match_all('/(?:DNS Servers|DNS[^:]*):\s*([^\s]+)/i', $raw, $matches);
    foreach (isset($matches[1]) ? $matches[1] : [] as $server) if (filter_var($server, FILTER_VALIDATE_IP)) $servers[] = $server;
    return ['source' => 'ipconfig /all', 'nameservers' => array_values(array_unique($servers)), 'raw' => substr($raw, 0, 4096)];
};
$hosts = static function ($family) {
    $path = $family === 'Windows' ? (string)getenv('SystemRoot') . '\\System32\\drivers\\etc\\hosts' : '/etc/hosts';
    $raw = is_readable($path) ? (string)@file_get_contents($path) : ''; $entries = [];
    foreach (preg_split('/\r?\n/', $raw) as $line) {
        $line = preg_replace('/\s*#.*/', '', trim($line));
        if ($line === '') continue;
        $parts = preg_split('/\s+/', $line);
        if (count($parts) > 1) $entries[] = ['ip' => array_shift($parts), 'hostnames' => $parts];
    }
    $result = ['path' => $path, 'entries' => $entries, 'raw' => substr($raw, 0, 4096)];
    if ($raw === '') $result['error'] = 'cannot read file';
    return $result;
};
return [
    'id' => 'NetworkInfoComponent', 'version' => '1.0.0',
    'handle' => static function ($action, $params) use ($osFamily, $interfaces, $arp, $routes, $dns, $hosts) {
        if ($action !== '' && $action !== 'collect') return ['code' => 400, 'msg' => 'unsupported network info action'];
        $family = $osFamily();
        return ['code' => 200, 'networkInfo' => [
            'interfaces' => $interfaces($family), 'arp' => $arp($family), 'routes' => $routes($family),
            'dnsConfig' => $dns($family), 'hosts' => $hosts($family),
            'os' => $family === 'Darwin' ? 'macOS' : $family]];
    }
];
