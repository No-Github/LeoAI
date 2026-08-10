<?php
/* PDO-backed database component. Connection translation is handled by phpcore. */
$dbGet = static function ($value, $key, $default = null) {
    return is_array($value) && array_key_exists($key, $value) ? $value[$key] : $default;
};
$dbEmpty = static function ($code, $message, $category = null, $sqlState = null, $retryable = false) {
    $result = ['code' => $code, 'msg' => $message, 'columns' => [], 'rows' => [],
        'rowCount' => 0, 'affectedRows' => 0, 'generatedKey' => null];
    if ($category !== null) {
        $result['errorCategory'] = $category;
        $result['sqlState'] = $sqlState;
        $result['retryable'] = (bool)$retryable;
    }
    return $result;
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
    'id' => 'DatabaseComponent', 'version' => '2.2.0',
    'handle' => static function ($action, $params) use ($dbGet, $dbEmpty, $dbColumn, $dbCell) {
        if ($action === 'capabilities') {
            $pdoAvailable = class_exists('PDO');
            $available = $pdoAvailable ? array_map('strtolower', PDO::getAvailableDrivers()) : [];
            sort($available);
            $drivers = [];
            foreach ($available as $driver) {
                $drivers[] = ['id' => $driver, 'name' => $driver,
                    'available' => true, 'registered' => true];
            }
            $requested = strtolower(trim((string)$dbGet($params, 'requestedDriver', '')));
            $requestedAvailable = $requested === '' || in_array($requested, $available, true);
            return ['code' => 200, 'msg' => '数据库运行时能力探测成功',
                'runtime' => 'php', 'provider' => 'pdo', 'available' => $pdoAvailable,
                'drivers' => $drivers,
                'requestedDriver' => ['id' => $requested, 'available' => $requestedAvailable,
                    'message' => $requested === '' ? '未指定 PDO 驱动'
                        : ($requestedAvailable ? 'PDO 驱动可用' : 'PDO 驱动不可用')],
                'constraints' => ['requiresInstalledDriver' => true,
                    'remoteInstallSupported' => false, 'customConnectorSupported' => true]];
        }
        if ($action !== '' && $action !== 'exec') return $dbEmpty(400, 'unsupported database action');
        if (!class_exists('PDO')) return $dbEmpty(
            503, 'PDO extension is missing', 'PROVIDER_NOT_FOUND', null, false);
        $provider = strtolower(trim((string)$dbGet($params, 'provider', '')));
        $pdoDriver = strtolower(trim((string)$dbGet($params, 'pdoDriver', '')));
        $dsn = trim((string)$dbGet($params, 'dsn', ''));
        $sql = trim((string)$dbGet($params, 'sql', ''));
        if ($provider !== 'pdo') return $dbEmpty(
            400, 'database provider must be pdo', 'INVALID_PROVIDER', null, false);
        if ($pdoDriver === '' || $dsn === '' || $sql === '') {
            return $dbEmpty(
                400, 'pdoDriver, dsn and sql are required', 'INVALID_ARGUMENT', null, false);
        }
        $available = array_map('strtolower', PDO::getAvailableDrivers());
        if (!in_array($pdoDriver, $available, true)) {
            return $dbEmpty(503, 'PDO driver is unavailable: ' . $pdoDriver
                . '; available drivers: ' . implode(', ', $available),
                'DRIVER_NOT_FOUND', null, false);
        }
        if (strtolower(substr($dsn, 0, strpos($dsn, ':'))) !== $pdoDriver) {
            return $dbEmpty(
                400, 'PDO driver and DSN do not match', 'URL_MISMATCH', null, false);
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
            $parameters = $dbGet($params, 'parameters', []);
            if (!is_array($parameters)) {
                return $dbEmpty(400, 'parameters must be an array', 'INVALID_ARGUMENT', null, false);
            }
            $statement->execute(array_values($parameters));
            $columnCount = (int)$statement->columnCount(); $columns = []; $rows = [];
            $maxRows = max(1, min(100000, (int)$dbGet($params, 'maxRows', 1000)));
            $maxResultBytes = max(1024, min(16777216,
                (int)$dbGet($params, 'maxResultBytes', 4194304)));
            $resultBytes = 0; $truncated = false; $truncationReason = null;
            if ($columnCount > 0) {
                for ($index = 0; $index < $columnCount; $index++) $columns[] = $dbColumn($statement, $index);
                while (($row = $statement->fetch(PDO::FETCH_ASSOC)) !== false) {
                    if (count($rows) >= $maxRows) {
                        $truncated = true; $truncationReason = 'MAX_ROWS'; break;
                    }
                    foreach ($columns as $column) {
                        $name = $column['label'];
                        if (array_key_exists($name, $row)) $row[$name] = $dbCell($row[$name], $column);
                    }
                    $encodedRow = json_encode($row);
                    $rowBytes = $encodedRow === false ? 0 : strlen($encodedRow);
                    if ($resultBytes + $rowBytes > $maxResultBytes) {
                        $truncated = true; $truncationReason = 'MAX_RESULT_BYTES'; break;
                    }
                    $resultBytes += $rowBytes;
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
                'truncated' => $truncated, 'truncationReason' => $truncationReason,
                'resultBytes' => $resultBytes,
                'serverVersion' => $serverVersion,
                'runtimeMetadata' => ['provider' => 'pdo', 'driver' => $pdoDriver]];
            $statement->closeCursor(); $pdo = null;
            return $result;
        } catch (PDOException $error) {
            $state = (string)$error->getCode();
            $category = 'SQL_ERROR'; $code = 422; $retryable = false;
            if (strncmp($state, '08', 2) === 0) {
                $category = 'CONNECTION_ERROR'; $code = 503; $retryable = true;
            } elseif (strncmp($state, '28', 2) === 0) {
                $category = 'AUTHENTICATION_ERROR';
            } elseif (strncmp($state, '40', 2) === 0) {
                $category = 'TRANSACTION_ROLLBACK'; $retryable = true;
            }
            return $dbEmpty($code, 'database operation failed: ' . $error->getMessage(),
                $category, $state, $retryable);
        } catch (Exception $error) {
            return $dbEmpty(500, $error->getMessage(), 'EXECUTION_ERROR', null, false);
        }
    }
];
