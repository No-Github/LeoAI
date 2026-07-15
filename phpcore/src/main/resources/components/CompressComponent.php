<?php
$get = static function ($value, $key, $default = null) {
    return is_array($value) && array_key_exists($key, $value) ? $value[$key] : $default;
};
return [
    'id' => 'CompressComponent', 'version' => '1.0.0',
    'handle' => static function ($action, $params) use ($get) {
        if (!class_exists('ZipArchive')) throw new RuntimeException('ZipArchive extension is missing');
        $source = (string)$get($params, 'src', ''); $destination = (string)$get($params, 'des', '');
        if ($source === '' || $destination === '') throw new InvalidArgumentException('src and des are required');
        $zip = new ZipArchive();
        if ($zip->open($destination, ZipArchive::CREATE | ZipArchive::OVERWRITE) !== true) throw new RuntimeException('zip destination cannot be opened');
        try {
            if (is_file($source)) $zip->addFile($source, basename($source));
            elseif (is_dir($source)) {
                $base = rtrim(realpath($source) ?: $source, DIRECTORY_SEPARATOR) . DIRECTORY_SEPARATOR;
                $iterator = new RecursiveIteratorIterator(new RecursiveDirectoryIterator($source, FilesystemIterator::SKIP_DOTS));
                foreach ($iterator as $file) if ($file->isFile()) $zip->addFile($file->getPathname(), substr($file->getPathname(), strlen($base)));
            } else throw new RuntimeException('source does not exist');
        } finally { $zip->close(); }
        return ['code' => 200, 'success' => true, 'path' => $destination, 'size' => filesize($destination)];
    }
];
