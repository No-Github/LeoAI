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
$isWindows = static function () {
    return defined('PHP_OS_FAMILY') ? constant('PHP_OS_FAMILY') === 'Windows' : strpos(strtoupper(PHP_OS), 'WIN') === 0;
};
$valid = static function ($value, $field, $required = true) {
    $value = trim((string)$value);
    if (($required && $value === '') || strlen($value) > 4096 || preg_match('/[\r\n\0]/', $value)) {
        throw new InvalidArgumentException('invalid ' . $field);
    }
    return $value;
};
$parse = static function ($output, $maxResults = 5000) {
    $entries = []; $current = null;
    foreach (preg_split('/\r?\n/', (string)$output) as $line) {
        $trimmed = trim($line);
        if (preg_match('/^(HKEY_[A-Z_]+(?:\\\\.*)?)$/i', $trimmed)) {
            if (is_array($current)) $entries[] = $current;
            $current = ['keyPath' => $trimmed, 'values' => []];
        } elseif (is_array($current) && preg_match('/^\s*(.*?)\s{2,}(REG_[A-Z0-9_]+)\s{2,}(.*)$/i', $line, $match)) {
            $current['values'][] = ['name' => trim($match[1]) === '' ? '(Default)' : trim($match[1]),
                'type' => trim($match[2]), 'data' => trim($match[3])];
        }
        if (count($entries) >= $maxResults) break;
    }
    if (is_array($current) && count($entries) < $maxResults) $entries[] = $current;
    return $entries;
};
return [
    'id' => 'RegistryComponent', 'version' => '1.0.0',
    'handle' => static function ($action, $params) use ($get, $available, $run, $isWindows, $valid, $parse) {
        if (!$isWindows()) return ['code' => 400, 'msg' => 'Windows registry is available on Windows hosts'];
        if (!$available('exec') && !$available('shell_exec')) return ['code' => 503, 'msg' => 'registry command backend is unavailable'];
        $keyPath = $valid($get($params, 'keyPath', ''), 'keyPath');
        if ($action === 'query') {
            $recursive = (bool)$get($params, 'recursive', false);
            $result = $run('reg query ' . escapeshellarg($keyPath) . ($recursive ? ' /s' : ''));
            $entries = $parse($result['output']);
            return ['code' => 200, 'action' => 'query', 'keyPath' => $keyPath, 'recursive' => $recursive,
                'total' => count($entries), 'entries' => $entries, 'rawOutput' => $entries ? null : $result['output']];
        }
        if ($action === 'search') {
            $pattern = $valid($get($params, 'pattern', ''), 'pattern');
            $searchTarget = strtolower($valid($get($params, 'searchTarget', 'data'), 'searchTarget'));
            $maxResults = max(1, min(10000, (int)$get($params, 'maxResults', 500)));
            $switch = $searchTarget === 'key' ? '/k' : ($searchTarget === 'value' || $searchTarget === 'name' ? '/f' : '/d');
            $result = $run('reg query ' . escapeshellarg($keyPath) . ' /s /f ' . escapeshellarg($pattern) . ' ' . $switch);
            $entries = array_slice($parse($result['output'], $maxResults), 0, $maxResults);
            return ['code' => 200, 'action' => 'search', 'rootPath' => $keyPath, 'pattern' => $pattern,
                'searchIn' => $searchTarget, 'total' => count($entries), 'entries' => $entries];
        }
        if ($action === 'set') {
            $name = $valid($get($params, 'valueName', ''), 'valueName', false);
            $type = strtoupper($valid($get($params, 'valueType', 'REG_SZ'), 'valueType'));
            $data = $valid($get($params, 'valueData', ''), 'valueData', false);
            if (!preg_match('/^REG_(SZ|EXPAND_SZ|MULTI_SZ|DWORD|QWORD|BINARY|NONE)$/', $type)) return ['code' => 400, 'msg' => 'invalid valueType'];
            $cmd = 'reg add ' . escapeshellarg($keyPath) . ($name === '' ? ' /ve' : ' /v ' . escapeshellarg($name))
                . ' /t ' . $type . ' /d ' . escapeshellarg($data) . ((bool)$get($params, 'force', false) ? ' /f' : '');
            $result = $run($cmd);
            return ['code' => 200, 'action' => 'set', 'keyPath' => $keyPath, 'valueName' => $name,
                'valueType' => $type, 'output' => trim($result['output'])];
        }
        if ($action === 'delete') {
            $name = $valid($get($params, 'valueName', ''), 'valueName', false);
            $cmd = 'reg delete ' . escapeshellarg($keyPath) . ($name === '' ? '' : ' /v ' . escapeshellarg($name))
                . ((bool)$get($params, 'force', false) ? ' /f' : '');
            $result = $run($cmd);
            return ['code' => 200, 'action' => 'delete', 'keyPath' => $keyPath, 'valueName' => $name,
                'output' => trim($result['output'])];
        }
        if ($action === 'export') {
            $path = tempnam(sys_get_temp_dir(), substr(hash('sha256', __FILE__ . '|export'), 0, 8));
            $result = $run('reg export ' . escapeshellarg($keyPath) . ' ' . escapeshellarg($path) . ' /y');
            $content = is_file($path) ? @file_get_contents($path, false, null, 0, 4 * 1024 * 1024) : false;
            if (is_file($path)) @unlink($path);
            return ['code' => 200, 'action' => 'export', 'keyPath' => $keyPath,
                'content' => is_string($content) ? base64_encode($content) : '', 'encoding' => 'base64',
                'output' => trim($result['output'])];
        }
        return ['code' => 400, 'msg' => 'unsupported registry action'];
    }
];
