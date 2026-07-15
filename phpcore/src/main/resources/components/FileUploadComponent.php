<?php
$get = static function ($value, $key, $default = null) {
    return is_array($value) && array_key_exists($key, $value) ? $value[$key] : $default;
};
return [
    'id' => 'FileUploadComponent', 'version' => '1.0.0',
    'handle' => static function ($action, $params) use ($get) {
        $path = (string)$get($params, 'path', '');
        $offset = max(0, (int)$get($params, 'offset', 0));
        $data = $get($params, 'data', '');
        if (!is_string($data)) throw new InvalidArgumentException('data must be binary');
        $handle = fopen($path, 'c+b'); if ($handle === false) throw new RuntimeException('file cannot be opened');
        try {
            if (fseek($handle, $offset) !== 0) throw new RuntimeException('invalid offset');
            $written = fwrite($handle, $data); if ($written === false) throw new RuntimeException('file write failed');
            return ['code' => 200, 'success' => true, 'written' => $written,
                'offset' => $offset, 'nextOffset' => $offset + $written];
        } finally { fclose($handle); }
    }
];
