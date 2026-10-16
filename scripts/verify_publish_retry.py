"""Verify retry scheduling against a private Redis; never connect a broker or user database."""
import argparse
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default=shutil.which('java'))
    parser.add_argument('--redis-server', default=shutil.which('redis-server'))
    parser.add_argument('--maven', default=shutil.which('mvn'))
    args = parser.parse_args()
    if not all([args.java, args.redis_server, args.maven]):
        parser.error('Provide Java 8, redis-server and Maven executable paths')
    root = Path(__file__).resolve().parents[1]
    target = root / 'lifestylePicks-trade-service/target'
    target.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix='publish-retry-verify-', dir=target))
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 0))
        port = probe.getsockname()[1]
    if port == 6379:
        raise RuntimeError('Refusing to use the default Redis port')
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    redis_process = None
    verification = None
    with (work / 'redis.log').open('w', encoding='utf-8') as redis_log, \
            (work / 'verification.log').open('w', encoding='utf-8') as verification_log:
        try:
            redis_process = subprocess.Popen([
                args.redis_server, '--bind', '127.0.0.1', '--port', str(port),
                '--save', '', '--appendonly', 'no', '--dir', str(work),
            ], stdout=redis_log, stderr=subprocess.STDOUT, cwd=work, creationflags=flags)
            deadline = time.monotonic() + 15
            while True:
                if redis_process.poll() is not None:
                    raise RuntimeError('Private Redis exited; inspect ' + str(work / 'redis.log'))
                try:
                    with socket.create_connection(('127.0.0.1', port), timeout=1) as connection:
                        connection.sendall(b'*1\r\n$4\r\nPING\r\n')
                        if connection.recv(100).startswith(b'+PONG'):
                            break
                except OSError:
                    pass
                if time.monotonic() > deadline:
                    raise RuntimeError('Private Redis did not start')
                time.sleep(.1)
            java_home = Path(args.java).resolve().parent.parent
            env = dict(os.environ, JAVA_HOME=str(java_home),
                       PATH=str(java_home / 'bin') + os.pathsep + os.environ['PATH'],
                       TRADE_RETRY_TEST_REDIS_PORT=str(port), TRADE_RETRY_TEST_REDIS_ISOLATED='true',
                       RETRY_VERIFY_MAVEN=str(Path(args.maven).resolve()))
            tests = 'PublishRetryRedisIntegrationTest,RocketMqOrderLogicTest,SeckillOrderDeadLetterListenerTest,TradePropertiesTest'
            maven_args = ['-o', '-Dmaven.repo.local=./.maven-repository', '-B', '-pl',
                          'lifestylePicks-trade-service', '-am', '-Dtest=' + tests,
                          '-Dsurefire.failIfNoSpecifiedTests=false', 'package']
            if os.name == 'nt':
                shell = shutil.which('pwsh') or shutil.which('powershell')
                if not shell:
                    shell = str(Path(os.environ['SystemRoot']) / 'System32/WindowsPowerShell/v1.0/powershell.exe')
                # 所有 Maven 参数都是脚本内常量，不将外部路径拼入 shell 文本。
                command = [shell, '-NoProfile', '-Command',
                           '& $env:RETRY_VERIFY_MAVEN ' + ' '.join("'" + value + "'" for value in maven_args)
                           + '; exit $LASTEXITCODE']
            else:
                command = [args.maven] + maven_args
            verification = subprocess.Popen(command, cwd=root, env=env, stdout=verification_log,
                                            stderr=subprocess.STDOUT, creationflags=flags)
            code = verification.wait(timeout=240)
            output = (work / 'verification.log').read_text(encoding='utf-8', errors='replace')
            if code:
                raise RuntimeError('Verification failed:\n' + output[-10000:])
            print('\n'.join(line for line in output.splitlines() if 'Tests run:' in line or 'BUILD SUCCESS' in line))
            print('PASS: isolated Redis Lua scheduling/window/concurrency tests and mocked MQ logic; no broker used')
            print('Verification logs: ' + str(work))
        finally:
            for process in [verification, redis_process]:
                if process is not None and process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)


if __name__ == '__main__':
    main()
