<?php
$get = static function ($value, $key, $default = null) {
    return is_array($value) && array_key_exists($key, $value) ? $value[$key] : $default;
};
$toDsn = static function ($value) {
    if (strpos($value, 'jdbc:sqlite:') === 0) return substr($value, strlen('jdbc:'));
    if (preg_match('#^jdbc:(mysql|postgresql)://([^/:?]+)(?::(\d+))?/([^?]+)#', $value, $matches)) {
        $driver = $matches[1] === 'postgresql' ? 'pgsql' : 'mysql'; $dsn = $driver . ':host=' . $matches[2];
        if (!empty($matches[3])) $dsn .= ';port=' . $matches[3];
        return $dsn . ';dbname=' . rawurldecode($matches[4]);
    }
    return strpos($value, 'jdbc:') === 0 ? substr($value, 5) : $value;
};
return [
    'id' => 'DatabaseComponent', 'version' => '1.0.0',
    'handle' => static function ($action, $params) use ($toDsn, $get) {
        if (!class_exists('PDO')) throw new RuntimeException('PDO extension is missing');
        $pdo = new PDO($toDsn((string)$get($params, 'url', '')),
            (string)$get($params, 'user', ''), (string)$get($params, 'password', ''),
            [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);
        $sql = (string)$get($params, 'sql', ''); $statement = $pdo->query($sql);
        if ($statement === false) return ['affectedRows' => $pdo->exec($sql), 'rows' => [], 'columns' => []];
        $rows = $statement->fetchAll(PDO::FETCH_ASSOC);
        return ['rows' => $rows, 'columns' => $rows ? array_keys($rows[0]) : [], 'rowCount' => count($rows)];
    }
];
