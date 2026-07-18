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
        return ['output' => substr(implode("\n", $lines), 0, 1024 * 1024), 'status' => $status];
    }
    if ($available('shell_exec')) {
        $output = @shell_exec($command . ' 2>&1');
        return ['output' => is_string($output) ? substr($output, 0, 1024 * 1024) : '', 'status' => null];
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
$validName = static function ($value, $field) {
    $value = trim((string)$value);
    if ($value === '' || strlen($value) > 256 || preg_match('/[\r\n\0]/', $value)) throw new InvalidArgumentException('invalid ' . $field);
    return $value;
};
$unixUsers = static function () {
    $users = []; $lines = @file('/etc/passwd', FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES);
    if (!is_array($lines)) return $users;
    foreach ($lines as $line) {
        $parts = explode(':', $line);
        if (count($parts) < 7) continue;
        $uid = (int)$parts[2];
        $users[] = ['name' => $parts[0], 'uid' => $uid, 'gid' => (int)$parts[3], 'gecos' => $parts[4],
            'home' => $parts[5], 'shell' => $parts[6], 'system' => $uid < 1000];
    }
    return $users;
};
$unixGroups = static function () {
    $groups = []; $lines = @file('/etc/group', FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES);
    if (!is_array($lines)) return $groups;
    foreach ($lines as $line) {
        $parts = explode(':', $line);
        if (count($parts) < 4) continue;
        $groups[] = ['name' => $parts[0], 'gid' => (int)$parts[2],
            'members' => $parts[3] === '' ? [] : explode(',', $parts[3])];
    }
    return $groups;
};
$windowsUsers = static function () use ($run) {
    $result = $run('wmic useraccount get Name,SID,Disabled,LocalAccount,Domain /format:csv'); $users = [];
    foreach (preg_split('/\r?\n/', trim($result['output'])) as $line) {
        $row = str_getcsv($line);
        if (count($row) < 6 || strtolower(trim($row[1])) === 'disabled') continue;
        $users[] = ['name' => trim($row[4]), 'sid' => trim($row[5]), 'disabled' => strtolower(trim($row[1])) === 'true',
            'local' => strtolower(trim($row[3])) === 'true', 'domain' => trim($row[2])];
    }
    return $users;
};
$windowsGroups = static function () use ($run) {
    $result = $run('wmic group get Name,SID,LocalAccount,Domain /format:csv'); $groups = [];
    foreach (preg_split('/\r?\n/', trim($result['output'])) as $line) {
        $row = str_getcsv($line);
        if (count($row) < 5 || strtolower(trim($row[2])) === 'localaccount') continue;
        $groups[] = ['name' => trim($row[3]), 'sid' => trim($row[4]),
            'local' => strtolower(trim($row[2])) === 'true', 'domain' => trim($row[1])];
    }
    return $groups;
};
return [
    'id' => 'UserAccountComponent', 'version' => '1.0.0',
    'handle' => static function ($action, $params) use ($get, $run, $osFamily, $validName, $unixUsers, $unixGroups, $windowsUsers, $windowsGroups) {
        $family = $osFamily(); $os = $family === 'Windows' ? 'windows' : ($family === 'Darwin' ? 'macos' : 'linux');
        if ($action === 'listUsers') {
            $users = $family === 'Windows' ? $windowsUsers() : $unixUsers();
            return ['code' => 200, 'data' => ['action' => 'listUsers', 'os' => $os, 'total' => count($users), 'users' => $users]];
        }
        if ($action === 'listGroups') {
            $groups = $family === 'Windows' ? $windowsGroups() : $unixGroups();
            return ['code' => 200, 'data' => ['action' => 'listGroups', 'os' => $os, 'total' => count($groups), 'groups' => $groups]];
        }
        if ($action === 'queryUser') {
            $name = $validName($get($params, 'username', ''), 'username'); $detail = null;
            if ($family === 'Windows') {
                $result = $run('net user ' . escapeshellarg($name));
                $detail = ['name' => $name, 'rawOutput' => $result['output']];
            } else {
                foreach ($unixUsers() as $user) if ($user['name'] === $name) { $detail = $user; break; }
                $result = $run('id ' . escapeshellarg($name));
                if (is_array($detail)) $detail['identity'] = trim($result['output']);
            }
            return ['code' => $detail === null ? 404 : 200, 'data' => ['action' => 'queryUser', 'os' => $os,
                'username' => $name, 'detail' => $detail]];
        }
        if ($action === 'queryGroup') {
            $name = $validName($get($params, 'groupName', ''), 'groupName'); $detail = null;
            if ($family === 'Windows') {
                $result = $run('net localgroup ' . escapeshellarg($name));
                $detail = ['name' => $name, 'rawOutput' => $result['output']];
            } else foreach ($unixGroups() as $group) if ($group['name'] === $name) { $detail = $group; break; }
            return ['code' => $detail === null ? 404 : 200, 'data' => ['action' => 'queryGroup', 'os' => $os,
                'groupName' => $name, 'detail' => $detail]];
        }
        if ($action === 'whoami') {
            $result = $run($family === 'Windows' ? 'whoami /all' : 'id');
            $name = get_current_user();
            if (function_exists('posix_geteuid') && function_exists('posix_getpwuid')) {
                $record = @posix_getpwuid(posix_geteuid()); if (is_array($record) && isset($record['name'])) $name = $record['name'];
            }
            return ['code' => 200, 'data' => ['action' => 'whoami', 'os' => $os,
                'detail' => ['username' => $name, 'identity' => trim($result['output'])]]];
        }
        return ['code' => 400, 'msg' => 'unsupported user account action'];
    }
];
