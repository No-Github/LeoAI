<?php
$get = static function ($value, $key, $default = null) {
    return is_array($value) && array_key_exists($key, $value) ? $value[$key] : $default;
};
$isWindows = static function () {
    return defined('PHP_OS_FAMILY')
        ? constant('PHP_OS_FAMILY') === 'Windows' : strpos(strtoupper(PHP_OS), 'WIN') === 0;
};
$fileEntry = static function ($path) {
    $stat = @stat($path); $name = basename($path); if ($name === '') $name = $path;
    return ['name' => $name, 'path' => $path, 'isDirectory' => is_dir($path), 'isFile' => is_file($path),
        'size' => is_file($path) ? (int)filesize($path) : 0, 'modified' => $stat ? (int)$stat['mtime'] * 1000 : 0,
        'permissions' => substr(sprintf('%o', @fileperms($path)), -4), 'canRead' => is_readable($path),
        'canWrite' => is_writable($path), 'canExecute' => is_executable($path), 'exists' => file_exists($path),
        'extension' => is_file($path) ? pathinfo($path, PATHINFO_EXTENSION) : ''];
};
$deletePath = null;
$deletePath = static function ($path) use (&$deletePath) {
    if (is_link($path) || is_file($path)) return @unlink($path);
    if (!is_dir($path)) return false;
    $items = scandir($path); if ($items === false) return false;
    foreach ($items as $item) {
        if ($item === '.' || $item === '..') continue;
        if (!$deletePath($path . DIRECTORY_SEPARATOR . $item)) return false;
    }
    return @rmdir($path);
};
return [
    'id' => 'FileComponent',
    'version' => '1.0.0',
    'handle' => static function ($action, $params) use ($fileEntry, $deletePath, $get, $isWindows) {
        $path = (string)$get($params, 'path', '');
        if ($action === 'roots') {
            $roots = [DIRECTORY_SEPARATOR];
            if ($isWindows()) {
                $roots = []; foreach (range('A', 'Z') as $drive) if (is_dir($drive . ':\\')) $roots[] = $drive . ':\\';
            }
            return ['code' => 200, 'absolutePath' => DIRECTORY_SEPARATOR,
                'fileList' => array_map($fileEntry, $roots), 'count' => count($roots)];
        }
        if ($path === '') throw new InvalidArgumentException('path is required');
        if ($action === 'list') {
            $items = scandir($path); if ($items === false) throw new RuntimeException('directory cannot be read');
            $result = [];
            foreach ($items as $item) {
                if ($item === '.' || $item === '..') continue;
                $result[] = $fileEntry(rtrim($path, '/\\') . DIRECTORY_SEPARATOR . $item);
            }
            return ['code' => 200, 'absolutePath' => realpath($path) ?: $path,
                'fileList' => $result, 'count' => count($result)];
        }
        if ($action === 'md5') return ['code' => 200, 'md5' => md5_file($path), 'filePath' => $path, 'fileSize' => filesize($path)];
        if ($action === 'mkdir') { $ok = is_dir($path) || mkdir($path, 0777, true); return ['code' => $ok ? 200 : 500, 'success' => $ok, 'absolutePath' => $path]; }
        if ($action === 'delete') { $ok = $deletePath($path); return ['code' => $ok ? 200 : 500, 'success' => $ok]; }
        if ($action === 'create' || $action === 'edit') {
            $ok = file_put_contents($path, (string)$get($params, 'content', '')) !== false;
            return ['code' => $ok ? 200 : 500, 'success' => $ok, 'absolutePath' => $path];
        }
        if ($action === 'copy') { $target = (string)$get($params, 'destPath', ''); $ok = copy($path, $target); return ['code' => $ok ? 200 : 500, 'success' => $ok, 'newPath' => $target]; }
        if ($action === 'move') { $target = (string)$get($params, 'newPath', ''); $ok = rename($path, $target); return ['code' => $ok ? 200 : 500, 'success' => $ok, 'newPath' => $target]; }
        throw new InvalidArgumentException('unsupported file action: ' . $action);
    }
];
