<?php
$get = static function ($value, $key, $default = null) {
    return is_array($value) && array_key_exists($key, $value) ? $value[$key] : $default;
};
return [
    'id' => 'DecompressComponent', 'version' => '1.0.0',
    'handle' => static function ($action, $params) use ($get) {
        if (!class_exists('ZipArchive')) throw new RuntimeException('ZipArchive extension is missing');
        $source = (string)$get($params, 'src', ''); $destination = (string)$get($params, 'des', '');
        if ($source === '' || $destination === '') throw new InvalidArgumentException('src and des are required');
        $zip = new ZipArchive(); if ($zip->open($source) !== true) throw new RuntimeException('zip source cannot be opened');
        try {
            $root = rtrim(realpath($destination) ?: $destination, DIRECTORY_SEPARATOR);
            if (!is_dir($root) && !mkdir($root, 0777, true)) throw new RuntimeException('destination cannot be created');
            for ($index = 0; $index < $zip->numFiles; $index++) {
                $name = (string)$zip->getNameIndex($index); $normalized = str_replace('\\', '/', $name);
                if ($name === '' || substr($normalized, 0, 1) === '/' || preg_match('/(^|\/)\.\.($|\/)/', $normalized)) throw new RuntimeException('unsafe zip entry');
            }
            if (!$zip->extractTo($root)) throw new RuntimeException('zip extraction failed');
        } finally { $zip->close(); }
        return ['code' => 200, 'success' => true, 'path' => $root];
    }
];
