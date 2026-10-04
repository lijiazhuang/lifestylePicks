"""Exercise Gateway -> real shop service -> isolated MySQL/Redis, using stdlib only."""
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
    target = root / 'lifestylePicks-shop-service' / 'target'
    shop_jar = target / 'lifestylePicks-shop-service-0.0.1-SNAPSHOT.jar'
    gateway_jar = root / 'lifestylePicks-gateway/target/lifestylePicks-gateway-0.0.1-SNAPSHOT.jar'
    if not shop_jar.exists() or not gateway_jar.exists():
        parser.error('Build the reactor first')
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
                    for name in ['mysql', 'redis', 'shop', 'gateway']}

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
                fixture = (root / 'lifestylePicks-shop-service/src/main/resources/db/shop.sql').read_text(encoding='utf-8')
                sql('CREATE DATABASE hmdp_shop_verify CHARACTER SET utf8mb4; USE hmdp_shop_verify;\n' + fixture)
                redis_process = launch([args.redis_server, '--bind', '127.0.0.1', '--port', str(redis_port),
                                        '--save', '', '--appendonly', 'no', '--dir', str(work)], 'redis')
                wait_until(lambda: redis_command(redis_port, 'PING') == 'PONG', 15)
                redis_command(redis_port, 'HMSET', 'login:token:shop-test', 'id', 42, 'nickName', 'test')
                redis_command(redis_port, 'EXPIRE', 'login:token:shop-test', 120)
                env = dict(os.environ, REDIS_HOST='127.0.0.1', REDIS_PORT=str(redis_port),
                           REDIS_PASSWORD='', REDIS_DATABASE='0', SHOP_GEO_INITIALIZE='true',
                           SHOP_DB_URL='jdbc:mysql://127.0.0.1:%s/hmdp_shop_verify?useSSL=false&serverTimezone=UTC&characterEncoding=utf8' % mysql_port,
                           SHOP_DB_USERNAME='root', SHOP_DB_PASSWORD='')
                shop_process, shop_port = start_java(shop_jar, 'shop', env)
                wait_until(lambda: redis_command(redis_port, 'ZCARD', 'shop:geo:1') == 9, 15)
                env['SHOP_SERVICE_URI'] = 'http://127.0.0.1:%s' % shop_port
                env['MONOLITH_URI'] = 'http://127.0.0.1:%s' % backend.server_port
                env['USER_SERVICE_URI'] = env['MONOLITH_URI']
                env['CONTENT_SERVICE_URI'] = env['MONOLITH_URI']
                env['TRADE_SERVICE_URI'] = env['MONOLITH_URI']
                gateway_process, gateway_port = start_java(gateway_jar, 'gateway', env)

                def ok(path, token=None, method='GET', data=None):
                    status, result = request(gateway_port, path, token, method, data)
                    assert status == 200 and result.get('success') is True, (path, status, result)
                    return result.get('data')

                for prefix in ['', '/api']:
                    shop = ok(prefix + '/shop/1')
                    assert shop['id'] == 1 and shop['name'] == '103茶餐厅', shop
                    types = ok(prefix + '/shop-type/list')
                    assert len(types) == 10 and [t['sort'] for t in types] == sorted(t['sort'] for t in types)
                    assert len(ok(prefix + '/shop/of/type?typeId=1&current=1')) == 5
                    assert len(ok(prefix + '/shop/of/type?typeId=1&current=2')) == 4
                    assert len(ok(prefix + '/shop/of/name?name=103')) == 1
                    status, result = request(gateway_port, prefix + '/shop', method='PUT', data={'id': 1}, identity='999')
                    assert status == 401 and result['success'] is False, result
                assert redis_command(redis_port, 'TTL', 'cache:shop:1') > 1700
                status, missing = request(gateway_port, '/api/shop/999')
                assert status == 200 and missing['success'] is False
                assert redis_command(redis_port, 'GET', 'cache:shop:999') == ''
                assert 0 < redis_command(redis_port, 'TTL', 'cache:shop:999') <= 120

                nearby = '/api/shop/of/type?typeId=1&x=120.15&y=30.32'
                first, second = ok(nearby + '&current=1'), ok(nearby + '&current=2')
                assert len(first) == 5 and len(second) == 4
                distances = [shop['distance'] for shop in first + second]
                assert distances == sorted(distances) and max(distances) < 5000, distances
                assert ok(nearby + '&current=3') == []

                # 真正通过网关传递身份：店铺写接口调用 UserContext，无有效身份不会写库。
                ok('/api/shop', 'shop-test', 'PUT', {'id': 1, 'name': 'Shop Service Updated', 'typeId': 2})
                assert redis_command(redis_port, 'GET', 'cache:shop:1') is None
                assert ok('/shop/1')['name'] == 'Shop Service Updated'
                assert redis_command(redis_port, 'ZSCORE', 'shop:geo:1', 1) is None
                assert redis_command(redis_port, 'ZSCORE', 'shop:geo:2', 1) is not None
                assert all(shop['id'] != 1 for shop in ok(nearby))
                status, result = request(shop_port, '/shop', method='PUT', data={'id': 1})
                assert status == 401 and result['success'] is False
                status, result = request(shop_port, '/shop/1', identity='42,84')
                assert status == 400 and result['success'] is False

                request(gateway_port, '/shop/15')  # 预先缓存不存在的下一个自增 ID。
                create = {'name': 'New Shop Service Record', 'typeId': 1, 'images': 'test.jpg',
                          'address': 'test address', 'x': 120.15, 'y': 30.32,
                          'sold': 0, 'comments': 0, 'score': 40}
                new_id = ok('/shop', 'shop-test', 'POST', create)
                assert new_id == 15
                assert redis_command(redis_port, 'GET', 'cache:shop:15') is None
                assert ok('/api/shop/15')['name'] == create['name']
                assert redis_command(redis_port, 'ZSCORE', 'shop:geo:1', 15) is not None
                status, failed = request(gateway_port, '/api/shop', 'shop-test', 'POST', {'name': 'invalid record'})
                assert status == 200 and failed['success'] is False
                assert sql("SELECT COUNT(*) FROM hmdp_shop_verify.tb_shop WHERE name='invalid record';") == '0'

                fallback = ok('/user/me', 'shop-test')
                assert fallback['path'] == '/user/me'
                ok('/api/blog/hot')
                assert not any(call['path'].startswith(('/shop', '/shop-type')) for call in Backend.calls)
                stop(shop_process)
                before = len(Backend.calls)
                status, _ = request(gateway_port, '/api/shop/1')
                assert status >= 500 and len(Backend.calls) == before, 'Shop outage fell back to old monolith'
                print('PASS: actual MySQL 8 + Redis + shop service + gateway + common UserContext')
                print('      detail/type/name queries, SQL/GEO pagination, cache/null cache, writes,')
                print('      commit invalidation, GEO type changes, auth/spoofing, rollback and route isolation')
                print('Verification logs and isolated database: ' + str(work))
            except Exception:
                for name in logs:
                    print(name + '.log:\n' + (work / (name + '.log')).read_text(encoding='utf-8', errors='replace')[-7000:])
                raise
            finally:
                stop(gateway_process)
                stop(shop_process)
                if mysql_process is not None and mysql_process.poll() is None:
                    try:
                        sql('SHUTDOWN;', False)
                        mysql_process.wait(timeout=10)
                    except (subprocess.TimeoutExpired, OSError):
                        pass
                stop(mysql_process)
                stop(redis_process)
    finally:
        backend.shutdown()
        backend.server_close()
        for process in [gateway_process, shop_process, mysql_process, redis_process]:
            stop(process)


if __name__ == '__main__':
    main()
