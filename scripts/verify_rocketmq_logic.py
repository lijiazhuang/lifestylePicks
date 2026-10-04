"""Verify real Redis/MySQL order flow with a mocked RocketMQ client; no broker installation or connection."""
import argparse
from contextlib import ExitStack
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

from verify_gateway_auth import Backend, redis_command, stop, verification_directory


def free_port():
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 0))
        return probe.getsockname()[1]


def wait_until(check, timeout=30):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            value = check()
            if value:
                return value
        except (OSError, subprocess.CalledProcessError):
            pass
        time.sleep(0.2)
    raise RuntimeError('Timed out waiting for test process')


def request(port, path, token=None, method='GET', data=None, identity=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['authorization'] = token
    if identity is not None:
        headers['X-User-Id'] = identity
    body = json.dumps(data).encode() if data is not None else None
    req = urllib.request.Request('http://127.0.0.1:%s%s' % (port, path),
                                 data=body, headers=headers, method=method)
    try:
        response = urllib.request.urlopen(req, timeout=8)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, json.loads(response.read().decode('utf-8'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['java', 'redis-server', 'mysqld', 'mysql']:
        parser.add_argument('--' + name, default=shutil.which(name))
    args = parser.parse_args()
    if not all([args.java, args.redis_server, args.mysqld, args.mysql]):
        parser.error('Provide Java 8, redis-server, mysqld and mysql executable paths')
    root = Path(__file__).resolve().parents[1]
    target = root / 'lifestylePicks-trade-service' / 'target'
    if not shutil.which('mvn'):
        parser.error('Maven must be available in PATH')
    mysql_port, redis_port = free_port(), free_port()
    mysql_base = Path(args.mysqld).resolve().parent.parent
    backend = ThreadingHTTPServer(('127.0.0.1', 0), Backend)
    threading.Thread(target=backend.serve_forever, daemon=True).start()
    mysql_process = redis_process = shop_process = gateway_process = None
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    try:
        with verification_directory(target) as work, ExitStack() as stack:
            data_dir = work / 'mysql-data'
            tmp_dir = work / 'mysql-tmp'
            data_dir.mkdir()
            tmp_dir.mkdir()
            logs = {name: stack.enter_context((work / (name + '.log')).open('w'))
                    for name in ['mysql', 'redis', 'verification']}

            def launch(command, name, env=None):
                return subprocess.Popen(command, cwd=work, env=env, stdout=logs[name],
                                        stderr=subprocess.STDOUT, creationflags=flags)

            def sql(statement, require_success=True):
                result = subprocess.run([
                    args.mysql, '--no-defaults', '--protocol=TCP', '--host=127.0.0.1',
                    '--port=' + str(mysql_port), '--user=root', '--default-character-set=utf8mb4',
                    '--connect-timeout=2', '--batch', '--skip-column-names',
                ], input=statement, encoding='utf-8', capture_output=True, timeout=15, creationflags=flags)
                if require_success:
                    assert result.returncode == 0, result.stderr
                return result.stdout.strip() if result.returncode == 0 else None

            def start_java(jar, name, env):
                process = launch([args.java, '-Xms64m', '-Xmx256m', '-jar', str(jar),
                                  '--server.address=127.0.0.1', '--server.port=0'], name, env)

                def started():
                    output = (work / (name + '.log')).read_text(encoding='utf-8', errors='replace')
                    if process.poll() is not None:
                        raise RuntimeError(name + ' exited during startup')
                    match = re.search(r'(?:Tomcat|Netty) started on port\(s\): (\d+)', output)
                    return int(match.group(1)) if match else None
                try:
                    return process, wait_until(started, 50)
                except Exception:
                    stop(process)
                    raise

            try:
                initializer = launch([
                    args.mysqld, '--no-defaults', '--initialize-insecure', '--basedir=' + str(mysql_base),
                    '--datadir=' + str(data_dir), '--tmpdir=' + str(tmp_dir), '--console',
                ], 'mysql')
                try:
                    assert initializer.wait(timeout=50) == 0, 'MySQL initialization failed'
                finally:
                    stop(initializer)
                mysql_process = launch([
                    args.mysqld, '--no-defaults', '--basedir=' + str(mysql_base), '--datadir=' + str(data_dir),
                    '--tmpdir=' + str(tmp_dir), '--port=' + str(mysql_port), '--bind-address=127.0.0.1',
                    '--mysqlx=OFF', '--skip-log-bin', '--console',
                ], 'mysql')
                wait_until(lambda: sql('SELECT 1;', False) == '1', 35)
                fixture=(root/'lifestylePicks-trade-service/src/main/resources/db/trade.sql').read_text(encoding='utf-8')
                sql('CREATE DATABASE verify_trade_rocketmq CHARACTER SET utf8mb4; USE verify_trade_rocketmq;\n'+fixture)
                redis_process=launch([args.redis_server,'--bind','127.0.0.1','--port',str(redis_port),
                                      '--save','','--appendonly','no','--dir',str(work)],'redis')
                wait_until(lambda: redis_command(redis_port,'PING')=='PONG',15)
                java_home=Path(args.java).resolve().parent.parent
                env=dict(os.environ,JAVA_HOME=str(java_home),PATH=str(java_home/'bin')+os.pathsep+os.environ['PATH'],
                         TRADE_TEST_DB_URL='jdbc:mysql://127.0.0.1:%s/verify_trade_rocketmq?useSSL=false&serverTimezone=UTC&characterEncoding=utf8'%mysql_port,
                         TRADE_TEST_REDIS_PORT=str(redis_port),TRADE_VERIFY_MAVEN=shutil.which('mvn'))
                # 只启动 Maven/JUnit。测试显式排除 RocketMQ 自动配置，模拟 RocketMQTemplate。
                if os.name=='nt':
                    shell=shutil.which('pwsh') or shutil.which('powershell')
                    if not shell:
                        shell=str(Path(os.environ['SystemRoot'])/'System32/WindowsPowerShell/v1.0/powershell.exe')
                    command=[shell,'-NoProfile','-Command',
                             "& $env:TRADE_VERIFY_MAVEN '-Dmaven.repo.local=./.maven-repository' '-B' '-pl' 'lifestylePicks-trade-service' '-Dtest=RocketMqFlowIntegrationTest' 'test'; exit $LASTEXITCODE"]
                else:
                    command=[env['TRADE_VERIFY_MAVEN'],'-Dmaven.repo.local=./.maven-repository','-B','-pl','lifestylePicks-trade-service','-Dtest=RocketMqFlowIntegrationTest','test']
                verification=subprocess.Popen(command,cwd=root,env=env,stdout=logs['verification'],stderr=subprocess.STDOUT,creationflags=flags)
                try:
                    result=verification.wait(timeout=180)
                    output=(work/'verification.log').read_text(encoding='utf-8',errors='replace')
                    assert result==0,output[-12000:]
                    print('\n'.join(line for line in output.splitlines() if 'Tests run:' in line or 'BUILD SUCCESS' in line))
                    print('PASS: actual Redis/MySQL, mocked MQ publisher and direct consumer handler; no RocketMQ broker used')
                    print('Verification logs: '+str(work))
                finally:stop(verification)
            except Exception:
                for name in logs:
                    print(name+'.log:\n'+(work/(name+'.log')).read_text(encoding='utf-8',errors='replace')[-8000:])
                raise
            finally:
                if mysql_process is not None and mysql_process.poll() is None:
                    try:sql('SHUTDOWN;',False);mysql_process.wait(timeout=10)
                    except (subprocess.TimeoutExpired,OSError):pass
                stop(mysql_process);stop(redis_process)
    finally:
        backend.shutdown();backend.server_close();stop(mysql_process);stop(redis_process)


if __name__=='__main__':main()
