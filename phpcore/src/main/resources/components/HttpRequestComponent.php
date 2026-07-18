<?php
$get = static function ($value, $key, $default = null) {
    return is_array($value) && array_key_exists($key, $value) ? $value[$key] : $default;
};
$available = static function ($name) {
    if (!function_exists($name)) return false;
    $disabled = array_map('trim', explode(',', (string)ini_get('disable_functions')));
    return !in_array($name, $disabled, true);
};
$headerExists = static function ($headers, $name) {
    foreach ($headers as $key => $value) if (strcasecmp((string)$key, $name) === 0) return true;
    return false;
};
$normalizeHeaders = static function ($raw) {
    $headers = [];
    if (!is_array($raw)) return $headers;
    foreach ($raw as $name => $value) {
        $name = trim((string)$name);
        if ($name === '' || preg_match('/[\r\n:]/', $name)) continue;
        if (is_array($value)) $value = implode(', ', array_map('strval', $value));
        $value = trim(str_replace(["\r", "\n"], '', (string)$value));
        $headers[$name] = $value;
    }
    return $headers;
};
$appendHeader = static function (&$headers, $name, $value) {
    foreach ($headers as $key => $existing) {
        if (strcasecmp((string)$key, $name) !== 0) continue;
        if (is_array($existing)) $headers[$key][] = $value;
        else $headers[$key] = [$existing, $value];
        return;
    }
    $headers[$name] = $value;
};
$parseHeaderLines = static function ($lines) use ($appendHeader) {
    $headers = []; $statusCode = 0; $statusMessage = '';
    foreach ((array)$lines as $line) {
        $line = trim((string)$line);
        if (preg_match('#^HTTP/\S+\s+(\d{3})(?:\s+(.*))?$#i', $line, $match)) {
            $headers = []; $statusCode = (int)$match[1];
            $statusMessage = isset($match[2]) ? trim($match[2]) : '';
            continue;
        }
        $colon = strpos($line, ':');
        if ($colon === false || $colon < 1) continue;
        $appendHeader($headers, trim(substr($line, 0, $colon)), trim(substr($line, $colon + 1)));
    }
    return [$statusCode, $statusMessage, $headers];
};
$contentType = static function ($headers) {
    foreach ($headers as $name => $value) {
        if (strcasecmp((string)$name, 'Content-Type') !== 0) continue;
        return is_array($value) ? (string)end($value) : (string)$value;
    }
    return '';
};
$isText = static function ($type) {
    $type = strtolower((string)$type);
    if ($type === '') return true;
    return strpos($type, 'text/') === 0 || strpos($type, 'json') !== false
        || strpos($type, 'xml') !== false || strpos($type, 'javascript') !== false
        || strpos($type, 'x-www-form-urlencoded') !== false
        || strpos($type, 'yaml') !== false || strpos($type, 'toml') !== false
        || strpos($type, 'graphql') !== false;
};
$finish = static function ($statusCode, $statusMessage, $headers, $body, $meta) use ($contentType, $isText) {
    $result = array_merge([
        'code' => 200, 'statusCode' => (int)$statusCode,
        'statusMessage' => (string)$statusMessage, 'responseHeaders' => $headers,
        'bodySize' => strlen($body)
    ], $meta);
    $type = $contentType($headers);
    if ($isText($type)) {
        $result['body'] = $body; $result['bodyType'] = 'text';
    } else {
        $result['body'] = leo_binary($body); $result['bodyType'] = 'binary';
    }
    return $result;
};
$sendCurl = static function ($method, $url, $headers, $body, $connectTimeout, $readTimeout,
                             $followRedirects, $maxResponse) use ($appendHeader, $finish) {
    $handle = curl_init();
    if ($handle === false) throw new RuntimeException('curl_init failed');
    $responseBody = ''; $statusMessage = ''; $truncated = false;
    $headerLines = [];
    $headerCallback = static function ($curl, $line) use (&$headerLines) {
        $headerLines[] = rtrim((string)$line, "\r\n"); return strlen($line);
    };
    $writeCallback = static function ($curl, $chunk) use (&$responseBody, &$truncated, $maxResponse) {
        $remaining = $maxResponse - strlen($responseBody);
        if ($remaining <= 0) { $truncated = true; return 0; }
        if (strlen($chunk) > $remaining) {
            $responseBody .= substr($chunk, 0, $remaining); $truncated = true; return 0;
        }
        $responseBody .= $chunk; return strlen($chunk);
    };
    $headerList = [];
    foreach ($headers as $name => $value) $headerList[] = $name . ': ' . $value;
    try {
        curl_setopt($handle, CURLOPT_URL, $url);
        curl_setopt($handle, CURLOPT_CUSTOMREQUEST, $method);
        curl_setopt($handle, CURLOPT_HTTPHEADER, $headerList);
        // Set this before installing the bounded write callback. Some PHP/cURL
        // versions reset the active write handler when RETURNTRANSFER changes.
        curl_setopt($handle, CURLOPT_RETURNTRANSFER, true);
        curl_setopt($handle, CURLOPT_HEADERFUNCTION, $headerCallback);
        curl_setopt($handle, CURLOPT_WRITEFUNCTION, $writeCallback);
        curl_setopt($handle, CURLOPT_FOLLOWLOCATION, $followRedirects);
        curl_setopt($handle, CURLOPT_MAXREDIRS, 10);
        curl_setopt($handle, CURLOPT_CONNECTTIMEOUT_MS, $connectTimeout);
        curl_setopt($handle, CURLOPT_TIMEOUT_MS, $readTimeout);
        curl_setopt($handle, CURLOPT_SSL_VERIFYPEER, false);
        curl_setopt($handle, CURLOPT_SSL_VERIFYHOST, 0);
        if (defined('CURLOPT_PROTOCOLS') && defined('CURLPROTO_HTTP') && defined('CURLPROTO_HTTPS')) {
            curl_setopt($handle, CURLOPT_PROTOCOLS, CURLPROTO_HTTP | CURLPROTO_HTTPS);
        }
        if ($method === 'HEAD') curl_setopt($handle, CURLOPT_NOBODY, true);
        elseif ($body !== null) curl_setopt($handle, CURLOPT_POSTFIELDS, $body);
        $started = microtime(true); $ok = curl_exec($handle); $elapsed = (int)round((microtime(true) - $started) * 1000);
        $errorCode = curl_errno($handle); $error = curl_error($handle);
        if ($ok === false && !($truncated && defined('CURLE_WRITE_ERROR') && $errorCode === CURLE_WRITE_ERROR)) {
            throw new RuntimeException('curl request failed: ' . ($error !== '' ? $error : $errorCode));
        }
        $parsedHeaders = [];
        $statusInfo = defined('CURLINFO_RESPONSE_CODE') ? CURLINFO_RESPONSE_CODE : CURLINFO_HTTP_CODE;
        $statusCode = (int)curl_getinfo($handle, $statusInfo);
        foreach ($headerLines as $line) {
            if (preg_match('#^HTTP/\S+\s+(\d{3})(?:\s+(.*))?$#i', trim($line), $match)) {
                $parsedHeaders = []; $statusCode = (int)$match[1];
                $statusMessage = isset($match[2]) ? trim($match[2]) : ''; continue;
            }
            $colon = strpos($line, ':');
            if ($colon !== false && $colon > 0) {
                $appendHeader($parsedHeaders, trim(substr($line, 0, $colon)), trim(substr($line, $colon + 1)));
            }
        }
        $meta = [
            'backend' => 'curl', 'elapsedMs' => $elapsed,
            'effectiveUrl' => (string)curl_getinfo($handle, CURLINFO_EFFECTIVE_URL),
            'redirectCount' => (int)curl_getinfo($handle, CURLINFO_REDIRECT_COUNT)
        ];
        if ($truncated) { $meta['truncated'] = true; $meta['truncateReason'] = 'response exceeds 10MB limit'; }
        return $finish($statusCode, $statusMessage, $parsedHeaders, $responseBody, $meta);
    } finally { curl_close($handle); }
};
$sendStream = static function ($method, $url, $headers, $body, $connectTimeout, $readTimeout,
                               $followRedirects, $maxResponse) use ($parseHeaderLines, $finish) {
    $headerLines = [];
    foreach ($headers as $name => $value) $headerLines[] = $name . ': ' . $value;
    $seconds = max($connectTimeout, $readTimeout) / 1000;
    $http = [
        'method' => $method, 'header' => implode("\r\n", $headerLines),
        'ignore_errors' => true, 'timeout' => max(1, $seconds),
        'follow_location' => $followRedirects ? 1 : 0, 'max_redirects' => 10,
        'protocol_version' => 1.1
    ];
    if ($method !== 'HEAD' && $body !== null) $http['content'] = $body;
    $context = stream_context_create([
        'http' => $http,
        'ssl' => ['verify_peer' => false, 'verify_peer_name' => false, 'allow_self_signed' => true]
    ]);
    $started = microtime(true); $stream = @fopen($url, 'rb', false, $context);
    if ($stream === false) throw new RuntimeException('HTTP stream request failed');
    $responseBody = ''; $truncated = false;
    try {
        while (!feof($stream)) {
            $chunk = fread($stream, min(8192, $maxResponse - strlen($responseBody) + 1));
            if ($chunk === false) throw new RuntimeException('HTTP stream read failed');
            if ($chunk === '') continue;
            $remaining = $maxResponse - strlen($responseBody);
            if (strlen($chunk) > $remaining) {
                if ($remaining > 0) $responseBody .= substr($chunk, 0, $remaining);
                $truncated = true; break;
            }
            $responseBody .= $chunk;
        }
        $metaData = stream_get_meta_data($stream);
    } finally { fclose($stream); }
    $elapsed = (int)round((microtime(true) - $started) * 1000);
    $parsed = $parseHeaderLines(isset($metaData['wrapper_data']) ? $metaData['wrapper_data'] : []);
    $meta = ['backend' => 'stream', 'elapsedMs' => $elapsed,
        'effectiveUrl' => isset($metaData['uri']) ? (string)$metaData['uri'] : $url, 'redirectCount' => 0];
    if ($truncated) { $meta['truncated'] = true; $meta['truncateReason'] = 'response exceeds 10MB limit'; }
    return $finish($parsed[0], $parsed[1], $parsed[2], $responseBody, $meta);
};
$defaultProfile = static function () {
    $family = defined('PHP_OS_FAMILY') ? constant('PHP_OS_FAMILY') : (strpos(strtoupper(PHP_OS), 'WIN') === 0 ? 'Windows' : PHP_OS);
    $selector = hexdec(substr(hash('sha256', __FILE__ . '|http-profile'), 0, 2)) % 2;
    if ($family === 'Darwin') {
        $version = $selector === 0 ? '17.5' : '17.6';
        $agent = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/' . $version . ' Safari/605.1.15';
        $accept = 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8';
    } elseif ($family === 'Windows') {
        $browser = $selector === 0 ? 'Chrome/124.0.0.0 Safari/537.36' : 'Chrome/124.0.0.0 Safari/537.36 Edg/124.0.0.0';
        $agent = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) ' . $browser;
        $accept = 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8';
    } else {
        $version = $selector === 0 ? '124.0' : '125.0';
        $agent = 'Mozilla/5.0 (X11; Linux x86_64; rv:' . $version . ') Gecko/20100101 Firefox/' . $version;
        $accept = 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8';
    }
    return ['User-Agent' => $agent, 'Accept' => $accept,
        'Accept-Language' => $selector === 0 ? 'zh-CN,zh;q=0.9,en;q=0.7' : 'en-US,en;q=0.9'];
};
return [
    'id' => 'HttpRequestComponent', 'version' => '1.0.0',
    'handle' => static function ($action, $params) use (
        $get, $available, $normalizeHeaders, $headerExists, $sendCurl, $sendStream, $defaultProfile
    ) {
        $method = strtoupper(trim((string)$get($params, 'method', 'GET')));
        $url = trim((string)$get($params, 'url', ''));
        if ($url === '' || !preg_match('#^https?://#i', $url)) throw new InvalidArgumentException('http/https url is required');
        if (!preg_match('/^[A-Z][A-Z0-9!#$%&\'*+.^_`|~-]{0,31}$/', $method)) throw new InvalidArgumentException('invalid HTTP method');
        $connectTimeout = max(100, min(300000, (int)$get($params, 'connectTimeout', 10000)));
        $readTimeout = max(100, min(300000, (int)$get($params, 'readTimeout', 30000)));
        $followRedirects = (bool)$get($params, 'followRedirects', true);
        $headers = $normalizeHeaders($get($params, 'headers', []));
        $profile = $defaultProfile();
        foreach ($profile as $name => $value) if (!$headerExists($headers, $name)) $headers[$name] = $value;
        $body = array_key_exists('body', $params) ? (string)$params['body'] : null;
        if ($body !== null && strlen($body) > 10 * 1024 * 1024) throw new LengthException('request body exceeds 10MB limit');
        if ($available('curl_init')) return $sendCurl($method, $url, $headers, $body,
            $connectTimeout, $readTimeout, $followRedirects, 10 * 1024 * 1024);
        if ($available('stream_context_create') && $available('fopen')) return $sendStream($method, $url,
            $headers, $body, $connectTimeout, $readTimeout, $followRedirects, 10 * 1024 * 1024);
        throw new RuntimeException('curl and HTTP stream wrappers are unavailable');
    }
];
