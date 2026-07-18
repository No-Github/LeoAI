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
        if (is_string($output)) return $output;
    }
    if ($available('exec')) {
        $lines = [];
        @exec($command . ' 2>&1', $lines);
        return implode("\n", $lines);
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
$humanSize = static function ($bytes) {
    $bytes = max(0, (float)$bytes);
    if ($bytes < 1) return '0 B';
    $units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];
    $index = 0;
    while ($bytes >= 1024 && $index < count($units) - 1) { $bytes /= 1024; $index++; }
    return ($index === 0 ? (string)(int)$bytes : (string)round($bytes, 2)) . ' ' . $units[$index];
};
$isVirtual = static function ($device, $mount) {
    if (preg_match('/^(tmpfs|devtmpfs|udev|sysfs|proc|cgroup2?|devfs|overlay|shm|run|none)$/', $device)) return true;
    return preg_match('#^/(?:sys|proc|dev(?:/shm)?)(?:/|$)#', $mount) === 1;
};
$summary = static function ($disks) use ($humanSize) {
    $total = 0; $used = 0; $free = 0; $byFsType = [];
    foreach ($disks as $disk) {
        $total += isset($disk['totalBytes']) ? (float)$disk['totalBytes'] : 0;
        $used += isset($disk['usedBytes']) ? (float)$disk['usedBytes'] : 0;
        $free += isset($disk['freeBytes']) ? (float)$disk['freeBytes'] : 0;
        if (!empty($disk['fsType'])) {
            $type = (string)$disk['fsType'];
            $byFsType[$type] = isset($byFsType[$type]) ? $byFsType[$type] + 1 : 1;
        }
    }
    return ['totalDisks' => count($disks), 'totalBytes' => (int)$total,
        'usedBytes' => (int)$used, 'freeBytes' => (int)$free,
        'total' => $humanSize($total), 'used' => $humanSize($used), 'free' => $humanSize($free),
        'usedPercent' => $total > 0 ? round($used * 100 / $total, 1) : 0,
        'byFsType' => $byFsType];
};
return [
    'id' => 'DiskComponent', 'version' => '1.0.0',
    'handle' => static function ($action, $params) use ($run, $osFamily, $humanSize, $isVirtual, $summary) {
        if ($action !== '' && $action !== 'list') return ['code' => 400, 'msg' => 'unsupported disk action'];
        $family = $osFamily(); $disks = [];
        if ($family === 'Windows') {
            foreach (range('A', 'Z') as $letter) {
                $mount = $letter . ':\\';
                if (!@is_dir($mount)) continue;
                $total = @disk_total_space($mount); $free = @disk_free_space($mount);
                if ($total === false || $free === false || $total <= 0) continue;
                $used = $total - $free;
                $disks[] = ['mount' => $mount, 'driveType' => 'Drive',
                    'totalBytes' => (int)$total, 'usedBytes' => (int)$used, 'freeBytes' => (int)$free,
                    'total' => $humanSize($total), 'used' => $humanSize($used), 'free' => $humanSize($free),
                    'usedPercent' => round($used * 100 / $total, 1)];
            }
            $os = 'windows';
        } else {
            if ($family === 'Linux' && is_readable('/proc/self/mounts')) {
                $seen = [];
                foreach ((array)@file('/proc/self/mounts', FILE_IGNORE_NEW_LINES) as $line) {
                    $parts = preg_split('/\s+/', trim($line)); if (count($parts) < 3) continue;
                    $device = str_replace(['\\040', '\\011', '\\134'], [' ', "\t", '\\'], $parts[0]);
                    $mount = str_replace(['\\040', '\\011', '\\134'], [' ', "\t", '\\'], $parts[1]);
                    if (isset($seen[$mount]) || $isVirtual($device, $mount) || !is_dir($mount)) continue;
                    $seen[$mount] = true; $total = @disk_total_space($mount); $free = @disk_free_space($mount);
                    if ($total === false || $free === false || $total <= 0) continue; $used = $total - $free;
                    $disks[] = ['device' => $device, 'mount' => $mount, 'fsType' => $parts[2],
                        'totalBytes' => (int)$total, 'usedBytes' => (int)$used, 'freeBytes' => (int)$free,
                        'total' => $humanSize($total), 'used' => $humanSize($used), 'free' => $humanSize($free),
                        'usedPercent' => round($used * 100 / $total, 1)];
                }
            } elseif ($family === 'Darwin') {
                $output = $run('df -Pk');
                $lines = preg_split('/\r?\n/', trim($output));
                foreach (array_slice($lines, 1) as $line) {
                    $parts = preg_split('/\s+/', trim($line), 6);
                    if (count($parts) !== 6 || !is_numeric($parts[1])) continue;
                    $device = $parts[0]; $mount = $parts[5];
                    if ($isVirtual($device, $mount)) continue;
                    $total = (float)$parts[1] * 1024; $used = (float)$parts[2] * 1024; $free = (float)$parts[3] * 1024;
                    $disks[] = ['device' => $device, 'mount' => $mount,
                        'totalBytes' => (int)$total, 'usedBytes' => (int)$used, 'freeBytes' => (int)$free,
                        'total' => $humanSize($total), 'used' => $humanSize($used), 'free' => $humanSize($free),
                        'usedPercent' => (float)rtrim($parts[4], '%')];
                }
            }
            if (!$disks) {
                $total = @disk_total_space(DIRECTORY_SEPARATOR); $free = @disk_free_space(DIRECTORY_SEPARATOR);
                if ($total !== false && $free !== false && $total > 0) {
                    $used = $total - $free;
                    $disks[] = ['device' => DIRECTORY_SEPARATOR, 'mount' => DIRECTORY_SEPARATOR,
                        'totalBytes' => (int)$total, 'usedBytes' => (int)$used, 'freeBytes' => (int)$free,
                        'total' => $humanSize($total), 'used' => $humanSize($used), 'free' => $humanSize($free),
                        'usedPercent' => round($used * 100 / $total, 1)];
                }
            }
            $os = $family === 'Darwin' ? 'macos' : 'linux';
        }
        return ['code' => 200, 'data' => ['os' => $os, 'total' => count($disks),
            'disks' => $disks, 'summary' => $summary($disks)]];
    }
];
