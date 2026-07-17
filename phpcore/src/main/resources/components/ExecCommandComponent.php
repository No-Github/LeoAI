<?php
$get = static function ($value, $key, $default = null) {
    return is_array($value) && array_key_exists($key, $value) ? $value[$key] : $default;
};
$available = static function ($name) {
    return function_exists($name) && !in_array($name,
        array_map('trim', explode(',', (string)ini_get('disable_functions'))), true);
};
$baseDirectory = rtrim((string)sys_get_temp_dir(), DIRECTORY_SEPARATOR)
    . DIRECTORY_SEPARATOR . '.leo-php-terminal';
$phpBinary = defined('PHP_BINARY') ? (string)constant('PHP_BINARY') : '';
$instanceId = substr(hash('sha256', php_uname('n') . '|' . __FILE__ . '|' . $phpBinary), 0, 16);
$pathsForKey = static function ($key) use ($baseDirectory) {
    $prefix = $baseDirectory . DIRECTORY_SEPARATOR . $key;
    return [
        'state' => $prefix . '.json',
        'output' => $prefix . '.out',
        'input' => $prefix . '.in',
        'size' => $prefix . '.size',
        'bridge' => $prefix . '.py',
        'exit' => $prefix . '.exit',
        'lock' => $prefix . '.lock'
    ];
};
$sessionPaths = static function ($processId) use ($pathsForKey) {
    return $pathsForKey(hash('sha256', $processId));
};
$loadState = static function ($path) {
    if (!is_file($path)) return null;
    $decoded = json_decode((string)@file_get_contents($path), true);
    return is_array($decoded) ? $decoded : null;
};
$saveState = static function ($path, $state) {
    $encoded = json_encode($state);
    if (!is_string($encoded) || @file_put_contents($path, $encoded, LOCK_EX) === false) {
        throw new RuntimeException('failed to persist terminal state');
    }
};
$capture = static function ($command) use ($available) {
    if ($available('shell_exec')) return trim((string)@shell_exec($command . ' 2>/dev/null'));
    if ($available('exec')) {
        $lines = []; $code = 0;
        @exec($command . ' 2>/dev/null', $lines, $code);
        return $code === 0 ? trim(implode("\n", $lines)) : '';
    }
    return '';
};
$findCommand = static function ($name) use ($capture) {
    if (!preg_match('/^[A-Za-z0-9._-]+$/', $name)) return null;
    foreach (['/usr/bin/', '/bin/', '/usr/local/bin/', '/usr/sbin/', '/sbin/'] as $directory) {
        $candidate = $directory . $name;
        if (is_file($candidate) && is_executable($candidate)) return $candidate;
    }
    $resolved = $capture('command -v ' . $name);
    if ($resolved !== '' && strpos($resolved, "\n") === false && is_executable($resolved)) return $resolved;
    return null;
};
$isAlive = static function ($pid) use ($available) {
    $pid = (int)$pid;
    if ($pid <= 0) return false;
    if ($available('posix_kill')) return @posix_kill($pid, 0);
    if (DIRECTORY_SEPARATOR !== '\\' && $available('exec')) {
        $lines = []; $code = 1;
        @exec('/bin/kill -0 ' . $pid . ' 2>/dev/null', $lines, $code);
        return $code === 0;
    }
    return false;
};
$signalProcess = static function ($pid, $signal, $processGroup) use ($available) {
    $pid = (int)$pid; $signal = (int)$signal;
    if ($pid <= 0 || DIRECTORY_SEPARATOR === '\\') return;
    $target = $processGroup ? -$pid : $pid;
    if ($available('posix_kill')) {
        @posix_kill($target, $signal);
        return;
    }
    if ($available('exec')) @exec('/bin/kill -' . $signal . ' ' . $target . ' 2>/dev/null');
};
$removeSessionFiles = static function ($paths, $includeLock) {
    foreach (['state', 'output', 'input', 'size', 'bridge', 'exit'] as $name) @unlink($paths[$name]);
    if ($includeLock) @unlink($paths['lock']);
};
$stopPty = static function ($state) use ($isAlive, $signalProcess) {
    if (!is_array($state) || empty($state['pty']) || empty($state['pid'])) return;
    $pid = (int)$state['pid'];
    $processGroup = !empty($state['processGroup']);
    if (!$isAlive($pid)) return;
    $signalProcess($pid, 15, $processGroup);
    $deadline = microtime(true) + 0.75;
    while ($isAlive($pid) && microtime(true) < $deadline) usleep(25000);
    if ($isAlive($pid)) $signalProcess($pid, 9, $processGroup);
};

$pythonBridge = <<<'PYTHON_BRIDGE'
from __future__ import print_function
import errno, fcntl, os, pty, select, signal, struct, sys, termios, time

input_path, output_path, size_path, exit_path, shell_path, cwd = sys.argv[1:7]
MAX_OUTPUT = 10 * 1024 * 1024
running = [True]
child_pid = [0]

def forward_signal(signum, frame):
    running[0] = False
    if child_pid[0] > 0:
        try: os.kill(child_pid[0], signum)
        except OSError: pass

for signum in (signal.SIGTERM, signal.SIGHUP, signal.SIGINT):
    signal.signal(signum, forward_signal)

try: os.chdir(cwd)
except OSError: os.chdir(os.path.dirname(output_path))

environment = os.environ.copy()
environment['TERM'] = environment.get('TERM') or 'xterm-256color'
environment['COLORTERM'] = environment.get('COLORTERM') or 'truecolor'
environment['HISTFILE'] = os.devnull
pid, master_fd = pty.fork()
if pid == 0:
    os.execve(shell_path, [shell_path, '-i'], environment)

child_pid[0] = pid
input_fd = os.open(input_path, os.O_RDWR | os.O_NONBLOCK)
output_fd = os.open(output_path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
flags = fcntl.fcntl(master_fd, fcntl.F_GETFL)
fcntl.fcntl(master_fd, fcntl.F_SETFL, flags | os.O_NONBLOCK)
last_size = [None]

def write_all(fd, data):
    while data:
        try:
            written = os.write(fd, data)
            if written <= 0: return
            data = data[written:]
        except OSError as error:
            if error.errno in (errno.EINTR, errno.EAGAIN):
                time.sleep(0.01)
                continue
            raise

def apply_size():
    try:
        stream = open(size_path, 'rb'); raw = stream.read(64); stream.close()
        if raw == last_size[0]: return
        last_size[0] = raw
        if not isinstance(raw, str): raw = raw.decode('ascii', 'ignore')
        cols, rows = [int(value) for value in raw.strip().split(',')]
        cols = max(20, min(500, cols)); rows = max(5, min(200, rows))
        fcntl.ioctl(master_fd, termios.TIOCSWINSZ, struct.pack('HHHH', rows, cols, 0, 0))
        try: os.kill(pid, signal.SIGWINCH)
        except OSError: pass
    except (IOError, OSError, ValueError):
        pass

def append_output(data):
    try:
        if os.path.getsize(output_path) > MAX_OUTPUT:
            os.ftruncate(output_fd, 0)
            os.lseek(output_fd, 0, os.SEEK_END)
            write_all(output_fd, b'\r\n[terminal output rotated]\r\n')
    except OSError: pass
    write_all(output_fd, data)

exit_code = 0
try:
    apply_size()
    while running[0]:
        apply_size()
        ready = select.select([master_fd, input_fd], [], [], 0.20)[0]
        if master_fd in ready:
            try:
                data = os.read(master_fd, 65536)
                if not data: break
                append_output(data)
            except OSError as error:
                if error.errno not in (errno.EIO, errno.EAGAIN, errno.EINTR): raise
                if error.errno == errno.EIO: break
        if input_fd in ready:
            try:
                data = os.read(input_fd, 65536)
                if data: write_all(master_fd, data)
            except OSError as error:
                if error.errno not in (errno.EAGAIN, errno.EINTR): raise
        waited, status = os.waitpid(pid, os.WNOHANG)
        if waited == pid:
            exit_code = os.WEXITSTATUS(status) if os.WIFEXITED(status) else 128
            child_pid[0] = 0
            break
finally:
    if child_pid[0] > 0:
        try: os.kill(child_pid[0], signal.SIGTERM)
        except OSError: pass
        try: os.waitpid(child_pid[0], 0)
        except OSError: pass
    for descriptor in (master_fd, input_fd, output_fd):
        try: os.close(descriptor)
        except OSError: pass
    try:
        with open(exit_path, 'w') as stream:
            stream.write(str(exit_code))
    except IOError: pass
PYTHON_BRIDGE;

$createFifo = static function ($path) use ($available, $findCommand) {
    @unlink($path);
    if ($available('posix_mkfifo') && @posix_mkfifo($path, 0600)) return true;
    $mkfifo = $findCommand('mkfifo');
    if ($mkfifo === null) return false;
    if ($available('exec')) {
        $lines = []; $code = 1;
        @exec(escapeshellarg($mkfifo) . ' ' . escapeshellarg($path), $lines, $code);
        return $code === 0 && file_exists($path);
    }
    return false;
};
$selectShell = static function () {
    foreach (['/bin/sh', '/bin/bash', '/bin/zsh', '/bin/ksh'] as $candidate) {
        if (is_file($candidate) && is_executable($candidate)) return $candidate;
    }
    $configured = getenv('SHELL');
    if ($configured !== false && strpos($configured, "\0") === false
        && is_file($configured) && is_executable($configured)) return $configured;
    return null;
};
$launchDetached = static function ($runner, $useProcessGroup) use ($capture, $findCommand) {
    $setsid = $useProcessGroup ? $findCommand('setsid') : null;
    $nohup = $findCommand('nohup');
    $command = ($nohup === null ? '' : escapeshellarg($nohup) . ' ')
        . ($setsid === null ? '' : escapeshellarg($setsid) . ' ')
        . $runner . ' </dev/null >/dev/null 2>&1 & echo $!';
    $pidText = $capture('/bin/sh -c ' . escapeshellarg($command));
    $lines = preg_split('/\s+/', trim($pidText));
    $pid = (int)end($lines);
    return ['pid' => $pid, 'processGroup' => $setsid !== null];
};
$startPty = static function ($paths, $cols, $rows) use (
    $pythonBridge, $createFifo, $selectShell, $findCommand, $launchDetached, $isAlive, $stopPty
) {
    if (DIRECTORY_SEPARATOR === '\\') return null;
    $shell = $selectShell();
    if ($shell === null || !$createFifo($paths['input'])) return null;
    @file_put_contents($paths['output'], '', LOCK_EX);
    @file_put_contents($paths['size'], $cols . ',' . $rows, LOCK_EX);
    @unlink($paths['exit']);

    $candidates = [];
    $python = $findCommand('python3');
    if ($python === null) $python = $findCommand('python');
    if ($python !== null) {
        if (@file_put_contents($paths['bridge'], $pythonBridge, LOCK_EX) !== false) {
            @chmod($paths['bridge'], 0700);
            $candidates[] = [
                'runner' => escapeshellarg($python) . ' ' . escapeshellarg($paths['bridge'])
                    . ' ' . escapeshellarg($paths['input']) . ' ' . escapeshellarg($paths['output'])
                    . ' ' . escapeshellarg($paths['size']) . ' ' . escapeshellarg($paths['exit'])
                    . ' ' . escapeshellarg($shell) . ' ' . escapeshellarg(getcwd() ?: sys_get_temp_dir()),
                'backend' => basename($python) . '-pty', 'resizable' => true
            ];
        }
    }
    $failures = [];
    foreach ($candidates as $candidate) {
        @file_put_contents($paths['output'], '', LOCK_EX);
        @unlink($paths['exit']);
        $started = $launchDetached($candidate['runner'], true);
        usleep(300000);
        if ($isAlive($started['pid'])) return [
            'backend' => $candidate['backend'], 'pty' => true,
            'resizable' => !empty($candidate['resizable']),
            'backendFailures' => $failures,
            'pid' => $started['pid'], 'processGroup' => $started['processGroup'],
            'readOffset' => 0, 'cols' => $cols, 'rows' => $rows,
            'active' => true, 'createdAt' => time(), 'lastAccess' => time()
        ];
        $failures[] = $candidate['backend'];
        $stopPty(['pty' => true, 'pid' => $started['pid'],
            'processGroup' => $started['processGroup']]);
    }
    return ['startupFailed' => true, 'backendFailures' => $failures];
};
$writePty = static function ($path, $data) {
    if ($data === '') return 0;
    $stream = @fopen($path, 'r+');
    if ($stream === false) throw new RuntimeException('terminal input channel is unavailable');
    stream_set_blocking($stream, true);
    $length = strlen($data); $offset = 0;
    while ($offset < $length) {
        $written = @fwrite($stream, substr($data, $offset));
        if ($written === false || $written === 0) {
            fclose($stream);
            throw new RuntimeException('failed to write terminal input');
        }
        $offset += $written;
    }
    fflush($stream); fclose($stream);
    return $offset;
};
$readPty = static function ($path, &$state) {
    clearstatcache(true, $path);
    $size = is_file($path) ? (int)@filesize($path) : 0;
    $offset = isset($state['readOffset']) ? (int)$state['readOffset'] : 0;
    if ($offset < 0 || $offset > $size) $offset = 0;
    if ($size <= $offset) return '';
    $stream = @fopen($path, 'rb');
    if ($stream === false) return '';
    @fseek($stream, $offset);
    $data = (string)@fread($stream, min(1048576, $size - $offset));
    fclose($stream);
    $state['readOffset'] = $offset + strlen($data);
    return $data;
};

$newCommandState = static function ($backendFailures = []) {
    $cwd = getcwd();
    if ($cwd === false || !is_dir($cwd)) $cwd = sys_get_temp_dir();
    return [
        'backend' => DIRECTORY_SEPARATOR === '\\' ? 'windows-command' : 'unix-command',
        'pty' => false, 'resizable' => false,
        'backendFailures' => is_array($backendFailures) ? $backendFailures : [],
        'cwd' => $cwd, 'previousCwd' => '', 'buffer' => '', 'escape' => false,
        'skipLf' => false, 'active' => true, 'createdAt' => time(),
        'lastAccess' => time(), 'cols' => 80, 'rows' => 24
    ];
};
$appendOutput = static function ($path, $data) {
    if ($data === '') return;
    clearstatcache(true, $path);
    if (is_file($path) && (int)@filesize($path) > 10485760) {
        $current = (string)@file_get_contents($path);
        @file_put_contents($path, substr($current, -5242880), LOCK_EX);
    }
    if (@file_put_contents($path, $data, FILE_APPEND | LOCK_EX) === false) {
        throw new RuntimeException('failed to persist terminal output');
    }
};
$readOutput = static function ($path) {
    if (!is_file($path)) return '';
    $pending = (string)@file_get_contents($path);
    if ($pending === '') return '';
    $chunk = substr($pending, 0, 1048576);
    @file_put_contents($path, (string)substr($pending, strlen($chunk)), LOCK_EX);
    return $chunk;
};
$prompt = static function ($state) {
    $user = getenv(DIRECTORY_SEPARATOR === '\\' ? 'USERNAME' : 'USER');
    if ($user === false || $user === '') $user = 'php';
    $host = function_exists('gethostname') ? gethostname() : php_uname('n');
    if ($host === false || $host === '') $host = 'host';
    return "\033[32m" . $user . '@' . $host . "\033[0m:\033[34m" . $state['cwd'] . "\033[0m"
        . (DIRECTORY_SEPARATOR === '\\' ? '> ' : '$ ');
};
$stripQuotes = static function ($value) {
    $value = trim((string)$value); $length = strlen($value);
    if ($length >= 2 && (($value[0] === '"' && $value[$length - 1] === '"')
        || ($value[0] === "'" && $value[$length - 1] === "'"))) return substr($value, 1, -1);
    return $value;
};
$runCommand = static function ($command, &$state) use ($available, $stripQuotes) {
    $command = trim((string)$command);
    if ($command === '') return '';
    if ($command === 'clear' || $command === 'cls') return "\033[2J\033[H";
    if ($command === 'exit' || $command === 'logout') { $state['active'] = false; return "logout\n"; }
    if (preg_match('/^cd(?:\s+(.*))?$/s', $command, $match)) {
        $path = isset($match[1]) ? $stripQuotes($match[1]) : '';
        if ($path === '' || $path === '~') {
            $home = getenv(DIRECTORY_SEPARATOR === '\\' ? 'USERPROFILE' : 'HOME');
            $path = $home !== false ? $home : $state['cwd'];
        } elseif ($path === '-') {
            $path = $state['previousCwd'] !== '' ? $state['previousCwd'] : $state['cwd'];
        } elseif (!preg_match('/^(?:[A-Za-z]:[\\\\\/]|[\\\\\/])/', $path)) {
            $path = rtrim($state['cwd'], '/\\') . DIRECTORY_SEPARATOR . $path;
        }
        $resolved = realpath($path);
        if ($resolved === false || !is_dir($resolved)) return 'cd: no such directory: ' . $path . "\n";
        $state['previousCwd'] = $state['cwd']; $state['cwd'] = $resolved;
        return '';
    }
    if (!$available('proc_open')) throw new RuntimeException('proc_open is required for command fallback');
    $shell = DIRECTORY_SEPARATOR === '\\'
        ? 'cmd.exe /d /s /c ' . $command
        : '/bin/sh -c ' . escapeshellarg($command);
    $pipes = [];
    $process = proc_open($shell, [0 => ['pipe', 'r'], 1 => ['pipe', 'w'], 2 => ['redirect', 1]],
        $pipes, $state['cwd']);
    if (!is_resource($process)) throw new RuntimeException('proc_open failed');
    fclose($pipes[0]); stream_set_blocking($pipes[1], false);
    $output = ''; $started = microtime(true);
    while (true) {
        $chunk = (string)stream_get_contents($pipes[1]);
        if ($chunk !== '') $output .= substr($chunk, 0, max(0, 1048576 - strlen($output)));
        $status = proc_get_status($process);
        if (!$status['running']) break;
        if (microtime(true) - $started >= 60 || strlen($output) >= 1048576) {
            proc_terminate($process, 9); $output .= "\n[command stopped by terminal limit]"; break;
        }
        usleep(10000);
    }
    $output .= substr((string)stream_get_contents($pipes[1]), 0, max(0, 1048576 - strlen($output)));
    fclose($pipes[1]); proc_close($process);
    return str_replace(["\r\n", "\r"], "\n", $output);
};
$writeCommand = static function (&$state, $command, $outputPath) use ($appendOutput, $prompt, $runCommand) {
    $length = strlen($command);
    for ($index = 0; $index < $length; $index++) {
        $character = $command[$index]; $code = ord($character);
        if (!empty($state['escape'])) {
            if ($code >= 64 && $code <= 126) $state['escape'] = false;
            continue;
        }
        if ($code === 27) { $state['escape'] = true; continue; }
        if (!empty($state['skipLf'])) { $state['skipLf'] = false; if ($character === "\n") continue; }
        if ($character === "\r" || $character === "\n") {
            $appendOutput($outputPath, "\r\n");
            try {
                $result = $runCommand($state['buffer'], $state);
                if ($result !== '') $appendOutput($outputPath, str_replace("\n", "\r\n", $result));
            } catch (Exception $error) {
                $appendOutput($outputPath, "\033[31m" . $error->getMessage() . "\033[0m\r\n");
            }
            $state['buffer'] = ''; if ($character === "\r") $state['skipLf'] = true;
            if (!empty($state['active'])) $appendOutput($outputPath, $prompt($state));
        } elseif ($code === 3) {
            $state['buffer'] = ''; $appendOutput($outputPath, "^C\r\n" . $prompt($state));
        } elseif ($code === 8 || $code === 127) {
            if ($state['buffer'] !== '') {
                $state['buffer'] = substr($state['buffer'], 0, -1);
                $appendOutput($outputPath, "\b \b");
            }
        } elseif ($code >= 32 || $character === "\t") {
            $state['buffer'] .= $character; $appendOutput($outputPath, $character);
        }
    }
    return $length;
};
$cleanup = static function ($excludeKey) use (
    $baseDirectory, $loadState, $pathsForKey, $stopPty, $removeSessionFiles
) {
    $states = (array)glob($baseDirectory . DIRECTORY_SEPARATOR . '*.json');
    usort($states, static function ($left, $right) { return @filemtime($left) - @filemtime($right); });
    $removeCount = max(0, count($states) - 32);
    foreach ($states as $index => $statePath) {
        $key = basename($statePath, '.json');
        if ($key === $excludeKey) continue;
        if ($index >= $removeCount && @filemtime($statePath) >= time() - 1800) continue;
        $paths = $pathsForKey($key);
        $lock = @fopen($paths['lock'], 'c+');
        if ($lock === false || !@flock($lock, LOCK_EX | LOCK_NB)) { if ($lock !== false) fclose($lock); continue; }
        $stopPty($loadState($statePath));
        $removeSessionFiles($paths, false);
        flock($lock, LOCK_UN); fclose($lock); @unlink($paths['lock']);
    }
};

return [
    'id' => 'ExecCommandComponent',
    'version' => '2.1.0',
    'handle' => static function ($action, $params) use (
        $get, $baseDirectory, $sessionPaths, $loadState, $saveState, $startPty,
        $writePty, $readPty, $isAlive, $stopPty, $removeSessionFiles,
        $newCommandState, $appendOutput, $readOutput, $prompt,
        $writeCommand, $cleanup, $instanceId
    ) {
        $processId = trim((string)$get($params, 'processId', ''));
        if ($processId === '' || strlen($processId) > 128 || !preg_match('/^[A-Za-z0-9._-]+$/', $processId)) {
            throw new InvalidArgumentException('invalid processId');
        }
        if (!is_dir($baseDirectory) && !@mkdir($baseDirectory, 0700, true) && !is_dir($baseDirectory)) {
            throw new RuntimeException('failed to create terminal state directory');
        }
        $key = hash('sha256', $processId);
        $cleanup($key);
        $paths = $sessionPaths($processId);
        $lock = @fopen($paths['lock'], 'c+');
        if ($lock === false || !flock($lock, LOCK_EX)) throw new RuntimeException('failed to lock terminal state');
        $removeLock = false;
        try {
            $state = $loadState($paths['state']);
            if ($action === 'stop') {
                $stopPty($state); $removeSessionFiles($paths, false); $removeLock = true;
                return ['code' => 200, 'stopped' => true, 'alive' => false, 'instanceId' => $instanceId];
            }
            if ($action === 'read') {
                if (!is_array($state)) return ['code' => 200, 'data' => leo_binary(''), 'alive' => false,
                    'missing' => true, 'instanceId' => $instanceId];
                $data = !empty($state['pty']) ? $readPty($paths['output'], $state) : $readOutput($paths['output']);
                $alive = !empty($state['pty']) ? $isAlive($state['pid']) : !empty($state['active']);
                $state['active'] = $alive; $state['lastAccess'] = time(); $saveState($paths['state'], $state);
                $exitCode = is_file($paths['exit']) ? (int)trim((string)@file_get_contents($paths['exit'])) : null;
                return [
                    'code' => 200, 'data' => leo_binary($data), 'alive' => $alive,
                    'pty' => !empty($state['pty']), 'resizable' => !empty($state['resizable']),
                    'backend' => $state['backend'], 'exitCode' => $exitCode,
                    'instanceId' => $instanceId,
                    'backendFailures' => isset($state['backendFailures']) ? $state['backendFailures'] : []
                ];
            }
            if ($action === 'resize') {
                if (!is_array($state)) throw new RuntimeException('terminal session is not initialized');
                $value = trim((string)$get($params, 'cmd', ''));
                if (!preg_match('/^(\d{1,3}),(\d{1,3})$/', $value, $match)) {
                    throw new InvalidArgumentException('resize expects cols,rows');
                }
                $cols = max(20, min(500, (int)$match[1])); $rows = max(5, min(200, (int)$match[2]));
                $resizable = !empty($state['resizable']);
                if ($resizable) @file_put_contents($paths['size'], $cols . ',' . $rows, LOCK_EX);
                $state['cols'] = $cols; $state['rows'] = $rows; $state['lastAccess'] = time();
                $saveState($paths['state'], $state);
                return [
                    'code' => 200, 'cols' => $cols, 'rows' => $rows,
                    'pty' => !empty($state['pty']), 'resizable' => $resizable, 'resized' => $resizable,
                    'instanceId' => $instanceId
                ];
            }
            if ($action !== 'write') throw new InvalidArgumentException('unsupported terminal action');
            $command = (string)$get($params, 'cmd', '');
            if ($command === 'init') {
                if (is_array($state)) $stopPty($state);
                $removeSessionFiles($paths, false);
                $state = $startPty($paths, 80, 24);
                if (!is_array($state) || !empty($state['startupFailed'])) {
                    $backendFailures = is_array($state) && isset($state['backendFailures'])
                        ? $state['backendFailures'] : [];
                    $state = $newCommandState($backendFailures);
                    @file_put_contents($paths['output'], '', LOCK_EX);
                    $appendOutput($paths['output'], "PHP command terminal ready\r\n" . $prompt($state));
                }
                $saveState($paths['state'], $state);
                return [
                    'code' => 200, 'initialized' => true, 'alive' => true,
                    'pty' => !empty($state['pty']), 'resizable' => !empty($state['resizable']),
                    'backend' => $state['backend'], 'instanceId' => $instanceId,
                    'backendFailures' => isset($state['backendFailures']) ? $state['backendFailures'] : []
                ];
            }
            if (!is_array($state)) throw new RuntimeException('terminal session is not initialized');
            if (!empty($state['pty'])) {
                if (!$isAlive($state['pid'])) throw new RuntimeException('terminal process has exited');
                $written = $writePty($paths['input'], $command);
            } else {
                $written = $writeCommand($state, $command, $paths['output']);
            }
            $state['lastAccess'] = time(); $saveState($paths['state'], $state);
            return [
                'code' => 200, 'written' => $written, 'alive' => !empty($state['active']),
                'pty' => !empty($state['pty']), 'resizable' => !empty($state['resizable']),
                'backend' => $state['backend'], 'instanceId' => $instanceId,
                'backendFailures' => isset($state['backendFailures']) ? $state['backendFailures'] : []
            ];
        } finally {
            flock($lock, LOCK_UN); fclose($lock);
            if ($removeLock) @unlink($paths['lock']);
        }
    }
];
