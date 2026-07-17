<?php
/* PDO-backed database component. Connection translation is handled by phpcore. */
$dbGet = static function ($value, $key, $default = null) {
    return is_array($value) && array_key_exists($key, $value) ? $value[$key] : $default;
};
$dbEmpty = static function ($code, $message) {
    return ['code' => $code, 'msg' => $message, 'columns' => [], 'rows' => [],
        'rowCount' => 0, 'affectedRows' => 0, 'generatedKey' => null];
};
$dbColumn = static function ($statement, $index) {
    $meta = false;
    try { $meta = $statement->getColumnMeta($index); } catch (Exception $ignored) { $meta = false; }
    if (!is_array($meta)) $meta = [];
    $name = isset($meta['name']) && $meta['name'] !== '' ? (string)$meta['name'] : 'column_' . ($index + 1);
    $flags = isset($meta['flags']) && is_array($meta['flags']) ? array_values($meta['flags']) : [];
    $type = isset($meta['native_type']) && $meta['native_type'] !== '' ? (string)$meta['native_type'] : 'UNKNOWN';
    if (in_array('blob', array_map('strtolower', $flags), true)
        || (isset($meta['pdo_type']) && (int)$meta['pdo_type'] === PDO::PARAM_LOB)) $type = 'BLOB';
    $column = ['name' => $name, 'label' => $name, 'type' => $type, 'nativeType' => $type];
    if (isset($meta['len']) && is_numeric($meta['len']) && (int)$meta['len'] >= 0) $column['length'] = (int)$meta['len'];
    if (isset($meta['precision']) && is_numeric($meta['precision'])) $column['precision'] = (int)$meta['precision'];
    if (isset($meta['table']) && $meta['table'] !== '') $column['table'] = (string)$meta['table'];
    return $column;
};
$dbCell = static function ($value, $column) {
    if (is_resource($value)) {
        $data = stream_get_contents($value); if ($data === false) $data = '';
        return leo_binary($data);
    }
    if (!is_string($value)) return $value;
    $upper = strtoupper((string)$column['type']);
    $binaryType = preg_match('/(^|[^A-Z])(BLOB|BINARY|VARBINARY|LONGVARBINARY|BYTEA|IMAGE|RAW)([^A-Z]|$)/', $upper) === 1;
    return $binaryType || preg_match('//u', $value) !== 1 ? leo_binary($value) : $value;
};

return [
    'id' => 'DatabaseComponent', 'version' => '2.0.0',
    'handle' => static function ($action, $params) use ($dbGet, $dbEmpty, $dbColumn, $dbCell) {
        if ($action !== '' && $action !== 'exec') return $dbEmpty(400, 'unsupported database action');
        if (!class_exists('PDO')) return $dbEmpty(503, 'PDO extension is missing');
        $provider = strtolower(trim((string)$dbGet($params, 'provider', '')));
        $pdoDriver = strtolower(trim((string)$dbGet($params, 'pdoDriver', '')));
        $dsn = trim((string)$dbGet($params, 'dsn', ''));
        $sql = trim((string)$dbGet($params, 'sql', ''));
        if ($provider !== 'pdo') return $dbEmpty(400, 'database provider must be pdo');
        if ($pdoDriver === '' || $dsn === '' || $sql === '') {
            return $dbEmpty(400, 'pdoDriver, dsn and sql are required');
        }
        $available = array_map('strtolower', PDO::getAvailableDrivers());
        if (!in_array($pdoDriver, $available, true)) {
            return $dbEmpty(503, 'PDO driver is unavailable: ' . $pdoDriver
                . '; available drivers: ' . implode(', ', $available));
        }
        if (strtolower(substr($dsn, 0, strpos($dsn, ':'))) !== $pdoDriver) {
            return $dbEmpty(400, 'PDO driver and DSN do not match');
        }
        try {
            $options = [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
                PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
                PDO::ATTR_STRINGIFY_FETCHES => false];
            $timeout = (int)$dbGet($params, 'timeoutSeconds', 0);
            if ($timeout > 0 && defined('PDO::ATTR_TIMEOUT')) $options[PDO::ATTR_TIMEOUT] = $timeout;
            $pdo = new PDO($dsn, (string)$dbGet($params, 'username', ''),
                (string)$dbGet($params, 'password', ''), $options);
            if (in_array($pdoDriver, ['mysql', 'pgsql'], true)) {
                try { $pdo->setAttribute(PDO::ATTR_EMULATE_PREPARES, false); } catch (Exception $ignored) { }
            }
            $statement = $pdo->prepare($sql);
            if ($statement === false) throw new RuntimeException('failed to prepare SQL statement');
            $statement->execute();
            $columnCount = (int)$statement->columnCount(); $columns = []; $rows = [];
            if ($columnCount > 0) {
                for ($index = 0; $index < $columnCount; $index++) $columns[] = $dbColumn($statement, $index);
                while (($row = $statement->fetch(PDO::FETCH_ASSOC)) !== false) {
                    foreach ($columns as $column) {
                        $name = $column['label'];
                        if (array_key_exists($name, $row)) $row[$name] = $dbCell($row[$name], $column);
                    }
                    $rows[] = $row;
                }
                $affectedRows = 0;
            } else {
                $affectedRows = max(0, (int)$statement->rowCount());
            }
            $serverVersion = '';
            try { $serverVersion = (string)$pdo->getAttribute(PDO::ATTR_SERVER_VERSION); } catch (Exception $ignored) { }
            $generatedKey = null;
            if ($columnCount === 0) {
                try { $generatedKey = (string)$pdo->lastInsertId(); } catch (Exception $ignored) { }
            }
            $result = ['code' => 200, 'msg' => '执行成功', 'columns' => $columns, 'rows' => $rows,
                'rowCount' => count($rows), 'affectedRows' => $affectedRows, 'generatedKey' => $generatedKey,
                'serverVersion' => $serverVersion,
                'runtimeMetadata' => ['provider' => 'pdo', 'driver' => $pdoDriver]];
            $statement->closeCursor(); $pdo = null;
            return $result;
        } catch (PDOException $error) {
            return $dbEmpty(500, 'database operation failed: ' . $error->getMessage());
        } catch (Exception $error) {
            return $dbEmpty(500, $error->getMessage());
        }
    }
];
