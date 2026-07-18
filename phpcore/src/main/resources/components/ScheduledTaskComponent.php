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
$osFamily = static function () {
    if (defined('PHP_OS_FAMILY')) return constant('PHP_OS_FAMILY');
    return strpos(strtoupper(PHP_OS), 'WIN') === 0 ? 'Windows' : 'Unix';
};
$valid = static function ($value, $field) {
    $value = trim((string)$value);
    if ($value === '' || strlen($value) > 2048 || preg_match('/[\r\n\0]/', $value)) throw new InvalidArgumentException('invalid ' . $field);
    return $value;
};
$taskName = static function ($command) {
    $command = trim((string)$command); $parts = preg_split('/\s+/', $command);
    return $command === '' ? '' : basename(trim($parts[0], "'\""));
};
$cronTasks = static function () use ($run, $taskName) {
    $raw = $run('crontab -l'); $tasks = [];
    foreach (preg_split('/\r?\n/', $raw['output']) as $line) {
        $trimmed = trim($line);
        if ($trimmed === '' || $trimmed[0] === '#' || preg_match('/^[A-Za-z_][A-Za-z0-9_]*=/', $trimmed)) continue;
        $parts = preg_split('/\s+/', $trimmed, 6);
        if (count($parts) < 6) continue;
        $cron = implode(' ', array_slice($parts, 0, 5)); $command = $parts[5];
        $tasks[] = ['taskName' => $taskName($command), 'cronExpression' => $cron, 'command' => $command,
            'source' => 'crontab', 'type' => 'cron', 'schedule' => $cron];
    }
    return [$tasks, $raw['output']];
};
$windowsTasks = static function () use ($run) {
    $raw = $run('schtasks /Query /FO CSV /V'); $lines = preg_split('/\r?\n/', trim($raw['output']));
    $tasks = []; $headers = null;
    foreach ($lines as $line) {
        $trimmed = trim($line);
        if ($trimmed === '' || $trimmed[0] !== '"') continue;
        $columns = str_getcsv($trimmed);
        if ($headers === null) { $headers = $columns; continue; }
        $row = [];
        foreach ($headers as $index => $header) $row[strtolower(trim($header))] = isset($columns[$index]) ? trim($columns[$index]) : '';
        $name = '';
        foreach (['taskname', 'task name', '任务名'] as $key) if (isset($row[$key])) { $name = $row[$key]; break; }
        if ($name === '') continue;
        $tasks[] = ['taskName' => $name,
            'nextRun' => isset($row['next run time']) ? $row['next run time'] : '',
            'status' => isset($row['status']) ? $row['status'] : '',
            'command' => isset($row['task to run']) ? $row['task to run'] : '', 'type' => 'schtasks'];
    }
    return [$tasks, $raw['output']];
};
$writeCron = static function ($content) use ($run) {
    $path = @tempnam(sys_get_temp_dir(), substr(hash('sha256', __FILE__ . '|task'), 0, 8));
    if ($path === false) return ['output' => 'temporary file unavailable', 'status' => 1];
    @file_put_contents($path, $content, LOCK_EX); @chmod($path, 0600);
    try { return $run('crontab ' . escapeshellarg($path)); } finally { @unlink($path); }
};
return [
    'id' => 'ScheduledTaskComponent', 'version' => '1.0.0',
    'handle' => static function ($action, $params) use ($get, $available, $run, $osFamily, $valid, $taskName, $cronTasks, $windowsTasks, $writeCron) {
        if (!$available('exec') && !$available('shell_exec')) return ['code' => 503, 'msg' => 'scheduled-task command backend is unavailable'];
        $windows = $osFamily() === 'Windows';
        if ($action === 'list') {
            list($tasks, $raw) = $windows ? $windowsTasks() : $cronTasks();
            return ['code' => 200, 'data' => ['os' => $windows ? 'windows' : 'linux', 'total' => count($tasks),
                'tasks' => $tasks, 'diagnostics' => [$windows ? 'source=schtasks' : 'source=crontab']]];
        }
        if ($action === 'createWindows') {
            if (!$windows) return ['code' => 400, 'msg' => 'Windows task creation requires Windows'];
            $name = $valid($get($params, 'taskName', ''), 'taskName'); $commandValue = $valid($get($params, 'command', ''), 'command');
            $schedule = strtoupper($valid($get($params, 'schedule', ''), 'schedule'));
            if (!in_array($schedule, ['MINUTE', 'HOURLY', 'DAILY', 'WEEKLY', 'MONTHLY', 'ONCE', 'ONSTART', 'ONLOGON', 'ONIDLE'], true)) return ['code' => 400, 'msg' => 'invalid schedule'];
            $cmd = 'schtasks /Create /TN ' . escapeshellarg($name) . ' /TR ' . escapeshellarg($commandValue) . ' /SC ' . $schedule;
            foreach (['modifier' => '/MO', 'startTime' => '/ST', 'startDate' => '/SD', 'runAs' => '/RU'] as $key => $flag) {
                $value = trim((string)$get($params, $key, '')); if ($value !== '') $cmd .= ' ' . $flag . ' ' . escapeshellarg($value);
            }
            if ((bool)$get($params, 'force', true)) $cmd .= ' /F'; $result = $run($cmd);
            return ['code' => 200, 'action' => 'create', 'data' => ['taskName' => $name, 'command' => $commandValue,
                'schedule' => $schedule, 'output' => trim($result['output'])]];
        }
        if ($action === 'createLinux') {
            if ($windows) return ['code' => 400, 'msg' => 'cron task creation requires Unix'];
            $cron = $valid($get($params, 'cronExpression', ''), 'cronExpression'); $commandValue = $valid($get($params, 'command', ''), 'command');
            if (count(preg_split('/\s+/', $cron)) !== 5) return ['code' => 400, 'msg' => 'cronExpression must contain five fields'];
            list($tasks, $existing) = $cronTasks(); $content = rtrim($existing) . ($existing === '' ? '' : "\n") . $cron . ' ' . $commandValue . "\n";
            $result = $writeCron($content);
            return ['code' => 200, 'action' => 'create', 'data' => ['taskName' => $taskName($commandValue),
                'cronExpression' => $cron, 'command' => $commandValue, 'output' => trim($result['output'])]];
        }
        $name = $valid($get($params, 'taskName', ''), 'taskName');
        if ($action === 'query') {
            if ($windows) {
                $result = $run('schtasks /Query /TN ' . escapeshellarg($name) . ' /V /FO LIST');
                return ['code' => 200, 'action' => 'query', 'data' => ['taskName' => $name, 'detail' => [], 'rawOutput' => substr($result['output'], 0, 8192)]];
            }
            list($tasks, $raw) = $cronTasks(); $matched = array_values(array_filter($tasks, static function ($item) use ($name) { return $item['taskName'] === $name || strpos($item['command'], $name) !== false; }));
            return ['code' => 200, 'action' => 'query', 'data' => ['taskName' => $name, 'total' => count($matched), 'tasks' => $matched]];
        }
        if ($action === 'delete') {
            if ($windows) $result = $run('schtasks /Delete /TN ' . escapeshellarg($name) . ' /F');
            else {
                list($tasks, $raw) = $cronTasks(); $kept = [];
                foreach (preg_split('/\r?\n/', $raw) as $line) {
                    $parts = preg_split('/\s+/', trim($line), 6); $commandValue = count($parts) === 6 ? $parts[5] : '';
                    if ($commandValue !== '' && ($taskName($commandValue) === $name || strpos($commandValue, $name) !== false)) continue;
                    if ($line !== '') $kept[] = $line;
                }
                $result = $writeCron($kept ? implode("\n", $kept) . "\n" : '');
            }
            return ['code' => 200, 'action' => 'delete', 'data' => ['taskName' => $name, 'output' => trim($result['output'])]];
        }
        if (!in_array($action, ['run', 'enable', 'disable'], true)) return ['code' => 400, 'msg' => 'unsupported scheduled-task action'];
        if (!$windows) return ['code' => 400, 'msg' => $action . ' is supported for Windows scheduled tasks'];
        $flag = $action === 'run' ? '/Run' : '/Change'; $cmd = 'schtasks ' . $flag . ' /TN ' . escapeshellarg($name);
        if ($action !== 'run') $cmd .= $action === 'enable' ? ' /Enable' : ' /Disable';
        $result = $run($cmd);
        return ['code' => 200, 'action' => $action, 'data' => ['taskName' => $name, 'output' => trim($result['output'])]];
    }
];
