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
        return ['output' => substr(implode("\n", $lines), 0, 8 * 1024 * 1024), 'status' => $status];
    }
    if ($available('shell_exec')) {
        $output = @shell_exec($command . ' 2>&1');
        return ['output' => is_string($output) ? substr($output, 0, 8 * 1024 * 1024) : '', 'status' => null];
    }
    return ['output' => '', 'status' => null];
};
$osFamily = static function () {
    if (defined('PHP_OS_FAMILY')) return constant('PHP_OS_FAMILY');
    $name = strtoupper(PHP_OS);
    if (strpos($name, 'WIN') === 0) return 'Windows';
    if (strpos($name, 'DAR') === 0) return 'Darwin';
    return 'Linux';
};
$safeSource = static function ($source) {
    $source = trim((string)$source);
    if ($source === '' || strlen($source) > 4096 || preg_match('/[\r\n\0]/', $source)) throw new InvalidArgumentException('invalid source');
    if ($source[0] === '/' || preg_match('/^[A-Za-z]:[\\\\\/]/', $source)) {
        $normalized = str_replace('\\', '/', $source);
        if (strpos('/' . $normalized . '/', '/../') !== false || preg_match('#^/(proc|sys|dev)(/|$)#', $normalized)) {
            throw new InvalidArgumentException('invalid log path');
        }
    }
    return $source;
};
$isFileSource = static function ($source) {
    return is_file($source) || (strlen($source) > 0 && ($source[0] === '/' || preg_match('/^[A-Za-z]:[\\\\\/]/', $source)));
};
$readFileRange = static function ($path, $maxBytes, $cursor, $direction) {
    $size = is_file($path) ? (int)@filesize($path) : 0;
    $maxBytes = max(1024, min(16 * 1024 * 1024, (int)$maxBytes));
    $cursor = $cursor === null ? null : max(0, min($size, (int)$cursor));
    if ($cursor !== null && strtolower((string)$direction) === 'newer') { $start = $cursor; $end = min($size, $start + $maxBytes); }
    elseif ($cursor !== null && strtolower((string)$direction) === 'older') { $end = $cursor; $start = max(0, $end - $maxBytes); }
    else { $end = $size; $start = max(0, $end - $maxBytes); }
    $length = max(0, $end - $start); $content = '';
    $handle = @fopen($path, 'rb');
    if (is_resource($handle)) {
        @fseek($handle, $start); if ($length > 0) $content = (string)@fread($handle, $length); @fclose($handle);
    }
    if ($start > 0 && $content !== '') { $cut = strpos($content, "\n"); if ($cut !== false) { $start += $cut + 1; $content = substr($content, $cut + 1); } }
    $lines = preg_split('/\r?\n/', $content); if ($lines && end($lines) === '') array_pop($lines);
    return ['lines' => is_array($lines) ? $lines : [], 'startByte' => $start, 'endByte' => $end,
        'fileSize' => $size, 'truncated' => $start > 0 || $end < $size];
};
$parseLine = static function ($line, $source, $format) {
    $entry = ['source' => $source, 'message' => (string)$line, 'raw' => (string)$line];
    $trimmed = trim((string)$line);
    if (($format === 'json' || (strlen($trimmed) > 1 && $trimmed[0] === '{')) && function_exists('json_decode')) {
        $decoded = json_decode($trimmed, true);
        if (is_array($decoded)) foreach ($decoded as $key => $value) if (is_scalar($value) || $value === null) $entry[(string)$key] = $value;
    }
    if (preg_match('/^(\S+)\s+\S+\s+\S+\s+\[([^\]]+)\]\s+"([A-Z]+)\s+([^\s"]+)[^"]*"\s+(\d{3})\s+(\S+)(?:\s+"[^"]*"\s+"([^"]*)")?/', $line, $m)) {
        $entry['ip'] = $m[1]; $entry['timestamp'] = $m[2]; $entry['method'] = $m[3]; $entry['path'] = $m[4];
        $entry['status'] = (int)$m[5]; $entry['bytes'] = $m[6] === '-' ? 0 : (int)$m[6];
        if (isset($m[7])) $entry['userAgent'] = $m[7];
    } elseif (preg_match('/\b(DEBUG|INFO|NOTICE|WARN(?:ING)?|ERROR|CRITICAL|ALERT|EMERGENCY)\b/i', $line, $m)) {
        $entry['level'] = strtoupper($m[1]);
    }
    return $entry;
};
$matches = static function ($entry, $params) use ($get) {
    $keyword = trim((string)$get($params, 'keyword', ''));
    if ($keyword !== '' && stripos((string)$entry['raw'], $keyword) === false) return false;
    $level = trim((string)$get($params, 'level', ''));
    if ($level !== '' && (!isset($entry['level']) || strcasecmp((string)$entry['level'], $level) !== 0)) return false;
    $eventId = trim((string)$get($params, 'eventId', ''));
    if ($eventId !== '' && (!isset($entry['eventId']) || (string)$entry['eventId'] !== $eventId)) return false;
    $min = $get($params, 'minStatus', null); $max = $get($params, 'maxStatus', null);
    if ($min !== null && (!isset($entry['status']) || (int)$entry['status'] < (int)$min)) return false;
    if ($max !== null && (!isset($entry['status']) || (int)$entry['status'] > (int)$max)) return false;
    $ip = trim((string)$get($params, 'ipPrefix', '')); $path = trim((string)$get($params, 'pathPrefix', ''));
    if ($ip !== '' && (!isset($entry['ip']) || strpos((string)$entry['ip'], $ip) !== 0)) return false;
    if ($path !== '' && (!isset($entry['path']) || strpos((string)$entry['path'], $path) !== 0)) return false;
    return true;
};
$querySource = static function ($source, $params) use ($get, $run, $osFamily, $isFileSource, $readFileRange, $parseLine, $matches) {
    $maxEntries = max(1, min(10000, (int)$get($params, 'maxEntries', 200)));
    $maxBytes = max(1024, min(16 * 1024 * 1024, (int)$get($params, 'maxBytes', 2 * 1024 * 1024)));
    $format = strtolower(trim((string)$get($params, 'format', 'auto'))); $entries = []; $meta = [];
    if ($isFileSource($source)) {
        if (!is_file($source) || !is_readable($source)) return ['entries' => [], 'meta' => ['fileSize' => 0, 'startByte' => 0, 'endByte' => 0], 'error' => 'log file is not readable'];
        $range = $readFileRange($source, $maxBytes, $get($params, 'cursor', null), $get($params, 'direction', 'older'));
        foreach ($range['lines'] as $line) { $entry = $parseLine($line, $source, $format); if ($matches($entry, $params)) $entries[] = $entry; }
        if (strtolower((string)$get($params, 'direction', 'older')) !== 'newer' && count($entries) > $maxEntries) $entries = array_slice($entries, -$maxEntries);
        else $entries = array_slice($entries, 0, $maxEntries);
        unset($range['lines']); $meta = $range;
    } else {
        $family = $osFamily(); $arg = escapeshellarg($source);
        if ($family === 'Windows') $cmd = 'wevtutil qe ' . $arg . ' /c:' . $maxEntries . ' /rd:true /f:text';
        elseif ($family === 'Darwin') $cmd = 'log show --style compact --last 1h --predicate ' . escapeshellarg('subsystem == "' . $source . '"');
        else $cmd = ($source === 'journal' || $source === 'system') ? 'journalctl -n ' . $maxEntries . ' --no-pager' : 'journalctl -u ' . $arg . ' -n ' . $maxEntries . ' --no-pager';
        $result = $run($cmd);
        foreach (preg_split('/\r?\n/', trim($result['output'])) as $line) if ($line !== '') {
            $entry = $parseLine($line, $source, $format); if ($matches($entry, $params)) $entries[] = $entry;
        }
        $entries = array_slice($entries, -$maxEntries); $meta = ['endByte' => strlen($result['output'])];
    }
    return ['entries' => $entries, 'meta' => $meta];
};
return [
    'id' => 'EventLogComponent', 'version' => '1.0.0',
    'handle' => static function ($action, $params) use ($get, $run, $osFamily, $safeSource, $isFileSource, $readFileRange, $querySource) {
        $family = $osFamily(); $os = $family === 'Windows' ? 'windows' : ($family === 'Darwin' ? 'macos' : 'linux');
        if ($action === 'listSources') {
            $sources = [];
            if ($family === 'Windows') {
                $result = $run('wevtutil el'); foreach (preg_split('/\r?\n/', trim($result['output'])) as $line) if (trim($line) !== '') $sources[] = trim($line);
            } else {
                foreach (['/var/log/*.log', '/var/log/*log', '/private/var/log/*.log'] as $pattern) {
                    $files = glob($pattern); if (is_array($files)) foreach ($files as $file) if (is_file($file)) $sources[$file] = $file;
                }
                if ($family === 'Linux') $sources['journal'] = 'journal';
                if ($family === 'Darwin') $sources['system'] = 'system';
                $sources = array_values($sources);
            }
            sort($sources);
            return ['code' => 200, 'action' => 'listSources', 'os' => $os, 'total' => count($sources), 'sources' => array_slice($sources, 0, 5000)];
        }
        $source = $safeSource($get($params, 'source', $family === 'Windows' ? 'System' : 'journal'));
        if ($action === 'query') {
            $result = $querySource($source, $params);
            return ['code' => isset($result['error']) ? 404 : 200, 'action' => 'query', 'source' => $source,
                'total' => count($result['entries']), 'entries' => $result['entries'], 'meta' => $result['meta']]
                + (isset($result['error']) ? ['msg' => $result['error']] : []);
        }
        if ($action === 'meta') {
            if (!$isFileSource($source)) return ['code' => 200, 'action' => 'meta', 'source' => $source, 'format' => $get($params, 'format', 'system'),
                'size' => 0, 'sizeHuman' => 'system source', 'large' => false, 'lastModified' => null, 'lines' => []];
            if (!is_file($source)) return ['code' => 404, 'action' => 'meta', 'source' => $source, 'msg' => 'log file not found'];
            $size = (int)@filesize($source); $lineCount = max(0, min(1000, (int)$get($params, 'lines', 0))); $preview = [];
            if ($lineCount > 0) {
                $range = $readFileRange($source, min(4 * 1024 * 1024, max(65536, $lineCount * 1024)), null,
                    (bool)$get($params, 'fromTail', true) ? 'older' : 'newer');
                $preview = $range['lines']; $preview = (bool)$get($params, 'fromTail', true) ? array_slice($preview, -$lineCount) : array_slice($preview, 0, $lineCount);
            }
            return ['code' => 200, 'action' => 'meta', 'source' => $source, 'format' => $get($params, 'format', 'auto'),
                'size' => $size, 'sizeHuman' => round($size / 1024, 2) . ' KiB', 'large' => $size > 10 * 1024 * 1024,
                'lastModified' => @filemtime($source), 'firstLine' => $preview ? reset($preview) : null,
                'lastLine' => $preview ? end($preview) : null, 'lines' => $preview];
        }
        if ($action === 'stats') {
            if ($isFileSource($source) && is_file($source)) {
                $size = (int)@filesize($source); $range = $readFileRange($source, min($size, 8 * 1024 * 1024), null, 'older');
                return ['code' => 200, 'action' => 'stats', 'source' => $source,
                    'detail' => ['size' => $size, 'sampledLines' => count($range['lines']), 'lastModified' => @filemtime($source)]];
            }
            $result = $querySource($source, ['maxEntries' => 1000]);
            return ['code' => 200, 'action' => 'stats', 'source' => $source,
                'detail' => ['sampledEntries' => count($result['entries'])]];
        }
        if ($action === 'clear') {
            if ($isFileSource($source)) {
                $ok = is_file($source) && @file_put_contents($source, '', LOCK_EX) !== false;
                return ['code' => $ok ? 200 : 500, 'action' => 'clear', 'source' => $source, 'cleared' => $ok];
            }
            if ($family === 'Windows') $result = $run('wevtutil cl ' . escapeshellarg($source));
            else $result = ['output' => 'System journals use retention and rotation controls', 'status' => 0];
            return ['code' => 200, 'action' => 'clear', 'source' => $source, 'output' => trim($result['output'])];
        }
        if ($action === 'aggregate') {
            $query = $params; $query['maxEntries'] = max(1, min(100000, (int)$get($params, 'maxScan', 10000)));
            $result = $querySource($source, $query); $groupBy = trim((string)$get($params, 'groupBy', 'status')); $counts = [];
            foreach ($result['entries'] as $entry) {
                $key = isset($entry[$groupBy]) ? (string)$entry[$groupBy] : '(unknown)'; if (!isset($counts[$key])) $counts[$key] = 0; $counts[$key]++;
            }
            arsort($counts); $groups = []; $topN = max(1, min(1000, (int)$get($params, 'topN', 20)));
            foreach ($counts as $key => $count) { $groups[] = ['key' => $key, 'count' => $count]; if (count($groups) >= $topN) break; }
            return ['code' => 200, 'action' => 'aggregate', 'fastPath' => !$get($params, 'slow', false), 'groupBy' => $groupBy,
                'source' => $source, 'scanned' => count($result['entries']), 'unique' => count($counts),
                'total' => array_sum($counts), 'groups' => $groups];
        }
        return ['code' => 400, 'msg' => 'unsupported event log action'];
    }
];
