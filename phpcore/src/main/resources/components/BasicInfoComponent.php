<?php
$get = static function ($value, $key, $default = null) {
    return is_array($value) && array_key_exists($key, $value) ? $value[$key] : $default;
};
$available = static function ($name) {
    return function_exists($name) && !in_array($name,
        array_map('trim', explode(',', (string)ini_get('disable_functions'))), true);
};
$osFamily = static function () {
    if (defined('PHP_OS_FAMILY')) return constant('PHP_OS_FAMILY');
    $os = strtoupper(PHP_OS);
    if (strpos($os, 'WIN') === 0) return 'Windows';
    if (strpos($os, 'DAR') === 0) return 'Darwin';
    if (strpos($os, 'LIN') === 0) return 'Linux';
    return PHP_OS;
};
return [
    'id' => 'BasicInfoComponent',
    'version' => '1.0.0',
    'handle' => static function ($action, $params) use ($get, $available, $osFamily) {
        $documentRoot = (string)$get($_SERVER, 'DOCUMENT_ROOT', '');
        $serverSoftware = (string)$get($_SERVER, 'SERVER_SOFTWARE', '');
        $hostName = $available('gethostname') ? (string)gethostname() : php_uname('n');
        $userName = $available('get_current_user') ? get_current_user() : '';
        $extensions = array_values(get_loaded_extensions());
        sort($extensions);
        return ['BasicInfo' => [
            'OSInfo' => ['OSName' => $osFamily(), 'OSVersion' => php_uname('r'),
                'OSArch' => php_uname('m'), 'HostName' => $hostName],
            'UserInfo' => ['UserName' => $userName, 'UserDir' => getcwd(),
                'UserHome' => (string)$get($_SERVER, 'HOME', ''),
                'UserLanguage' => (string)$get($_SERVER, 'LANG', ''),
                'UserTimezone' => date_default_timezone_get()],
            'MiddlewareInfo' => ['MiddlewareType' => $serverSoftware !== '' ? $serverSoftware : PHP_SAPI,
                'ServerInfo' => $serverSoftware, 'ContextPath' => $documentRoot, 'Version' => PHP_VERSION],
            'PhpRuntimeInfo' => ['PHPVersion' => PHP_VERSION, 'SAPI' => PHP_SAPI,
                'Extensions' => $extensions, 'MemoryLimit' => (string)ini_get('memory_limit'),
                'MaxExecutionTime' => (string)ini_get('max_execution_time'),
                'OpenBasedir' => (string)ini_get('open_basedir'),
                'DisabledFunctions' => array_values(array_filter(array_map('trim', explode(',', (string)ini_get('disable_functions')))))],
            'ProcessInfo' => ['ProcessName' => PHP_SAPI, 'ProcessId' => getmypid(), 'CurrentPath' => getcwd()],
            'EnvironmentInfo' => is_array($_ENV) ? $_ENV : [],
            'HardwareInfo' => [], 'NetworkInfo' => [], 'FileSystemInfo' => []
        ]];
    }
];
