<?php
$get = static function ($value, $key, $default = null) {
    return is_array($value) && array_key_exists($key, $value) ? $value[$key] : $default;
};
return [
    'id' => 'FileDownloadComponent', 'version' => '1.0.0',
    'handle' => static function ($action, $params) use ($get) {
        $path = (string)$get($params, 'path', '');
        $offset = max(0, (int)$get($params, 'offset', 0));
        $size = max(1, min(4 * 1024 * 1024, (int)$get($params, 'size', 1048576)));
        $handle = fopen($path, 'rb'); if ($handle === false) throw new RuntimeException('file cannot be opened');
        try {
            if (fseek($handle, $offset) !== 0) throw new RuntimeException('invalid offset');
            $data = fread($handle, $size); if ($data === false) throw new RuntimeException('file read failed');
            $total = (int)filesize($path); $nextOffset = $offset + strlen($data); $complete = $nextOffset >= $total;
            return ['code' => $complete ? 200 : 100, 'data' => leo_binary($data), 'offset' => $offset,
                'length' => $total, 'bytesRead' => strlen($data), 'nextOffset' => $nextOffset, 'isComplete' => $complete];
        } finally { fclose($handle); }
    }
];
