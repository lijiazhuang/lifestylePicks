"""Exercise Gateway -> real user service -> isolated MySQL/Redis, using stdlib only."""
import argparse
import concurrent.futures
from datetime import datetime, timedelta, timezone
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
    legacy_jar = root.parent / "hm-dianping-backend/target/hm-dianping-0.0.1-SNAPSHOT.jar"
    target = root / 'lifestylePicks-user-service' / 'target'
    user_jar = target / 'lifestylePicks-user-service-0.0.1-SNAPSHOT.jar'
    gateway_jar = root / 'lifestylePicks-gateway/target/lifestylePicks-gateway-0.0.1-SNAPSHOT.jar'
    if not user_jar.exists() or not gateway_jar.exists() or not legacy_jar.exists():
        parser.error('Build the reactor and legacy backend JARs first')
    mysql_port, redis_port = free_port(), free_port()
    mysql_base = Path(args.mysqld).resolve().parent.parent
    backend = ThreadingHTTPServer(('127.0.0.1', 0), Backend)
    threading.Thread(target=backend.serve_forever, daemon=True).start()
    mysql_process = redis_process = user_process = gateway_process = legacy_process = None
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    try:
        with verification_directory(target) as work, ExitStack() as stack:
            data_dir = work / 'mysql-data'
            tmp_dir = work / 'mysql-tmp'
            data_dir.mkdir()
            tmp_dir.mkdir()
            logs = {name: stack.enter_context((work / (name + '.log')).open('w'))
                    for name in ['mysql', 'redis', 'user', 'legacy', 'gateway']}

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
                fixture = (root / 'lifestylePicks-user-service/src/main/resources/db/user.sql').read_text(encoding='utf-8')
                sql('CREATE DATABASE hmdp_user_verify CHARACTER SET utf8mb4; USE hmdp_user_verify;\n' + fixture)
                sql("INSERT INTO hmdp_user_verify.tb_user_info(user_id,city,introduce) VALUES(1,'Hangzhou','User Service Profile');")
                # 单体库故意没有用户表，真实内容请求必须通过用户服务获取资料。
                source = (root.parent / 'hm-dianping-backend/src/main/resources/db/hmdp.sql').read_text(encoding='utf-8')
                # 原秒杀表含零日期默认值，只在此独立测试会话兼容旧建表脚本。
                statements = ["SET SESSION sql_mode='NO_ENGINE_SUBSTITUTION';",
                              'CREATE DATABASE hmdp_legacy_verify CHARACTER SET utf8mb4; USE hmdp_legacy_verify;']
                for table in ['tb_blog', 'tb_blog_comments', 'tb_follow', 'tb_voucher', 'tb_seckill_voucher', 'tb_voucher_order']:
                    statements.append(re.search(r'CREATE TABLE `' + table + r'`.*?;\n', source, re.S).group(0))
                    statements.extend(line for line in source.splitlines() if line.startswith('INSERT INTO `' + table + '`'))
                statements.append("CREATE USER 'legacy'@'%' IDENTIFIED WITH mysql_native_password BY 'verify';")
                statements.append("GRANT ALL ON hmdp_legacy_verify.* TO 'legacy'@'%';")
                sql('\n'.join(statements))
                redis_process = launch([args.redis_server, '--bind', '127.0.0.1', '--port', str(redis_port),
                                        '--save', '', '--appendonly', 'no', '--dir', str(work)], 'redis')
                wait_until(lambda: redis_command(redis_port, 'PING') == 'PONG', 15)
                redis_command(redis_port, 'XGROUP', 'CREATE', 'stream.orders', 'g1', 0, 'MKSTREAM')
                env = dict(os.environ, REDIS_HOST='127.0.0.1', REDIS_PORT=str(redis_port),
                           REDIS_PASSWORD='', REDIS_DATABASE='0', TOKEN_TTL='120s', USER_LOG_CODE='false',
                           USER_DB_URL='jdbc:mysql://127.0.0.1:%s/hmdp_user_verify?useSSL=false&serverTimezone=UTC&characterEncoding=utf8' % mysql_port,
                           USER_DB_USERNAME='root', USER_DB_PASSWORD='')
                user_process, user_port = start_java(user_jar, 'user', env)
                env['USER_SERVICE_URI'] = 'http://127.0.0.1:%s' % user_port
                legacy_env = dict(env, SPRING_REDIS_HOST='127.0.0.1', SPRING_REDIS_PORT=str(redis_port),
                                  SPRING_REDIS_PASSWORD='', HMDP_LEGACY_CONTENT_ENABLED='true', SPRING_DATASOURCE_USERNAME='legacy', SPRING_DATASOURCE_PASSWORD='verify',
                                  SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:%s/hmdp_legacy_verify?useSSL=false&serverTimezone=UTC&characterEncoding=utf8' % mysql_port)
                legacy_process, legacy_port = start_java(legacy_jar, 'legacy', legacy_env)
                env['MONOLITH_URI'] = 'http://127.0.0.1:%s' % legacy_port
                env['CONTENT_SERVICE_URI'] = env['MONOLITH_URI']
                env['TRADE_SERVICE_URI'] = env['MONOLITH_URI']
                gateway_process, gateway_port = start_java(gateway_jar, 'gateway', env)

                def ok(path, token=None, method='GET', data=None):
                    status, result = request(gateway_port, path, token, method, data)
                    assert status == 200 and result.get('success') is True, (path, status, result)
                    return result.get('data')

                def issue(phone, prefix='/api'):
                    ok(prefix + '/user/code?phone=' + phone, method='POST')
                    code = redis_command(redis_port, 'GET', 'login:code:' + phone)
                    assert code and len(code) == 6 and code.isdigit()
                    assert 0 < redis_command(redis_port, 'TTL', 'login:code:' + phone) <= 120
                    return code

                for prefix in ['', '/api']:
                    status, result = request(gateway_port, prefix + '/user/me', identity='999')
                    assert status == 401 and result['success'] is False
                    status, result = request(gateway_port, prefix + '/user/code?phone=invalid', method='POST')
                    assert status == 200 and result['success'] is False
                phone = '13686869696'
                code = issue(phone)
                wrong = '000000' if code != '000000' else '111111'
                status, result = request(gateway_port, '/user/login', method='POST', data={'phone': phone, 'code': wrong})
                assert status == 200 and result['success'] is False
                assert redis_command(redis_port, 'GET', 'login:code:' + phone) == code
                token = ok('/api/user/login', method='POST', data={'phone': phone, 'code': code})
                assert isinstance(token, str) and len(token) == 32
                assert redis_command(redis_port, 'GET', 'login:code:' + phone) is None
                assert redis_command(redis_port, 'HGET', 'login:token:' + token, 'id') == '1'
                assert redis_command(redis_port, 'HGET', 'login:token:' + token, 'nickName') == '小鱼同学'
                assert 110 <= redis_command(redis_port, 'TTL', 'login:token:' + token) <= 120
                for prefix in ['', '/api']:
                    user = ok(prefix + '/user/me', token)
                    assert user['id'] == 1 and set(user) == {'id', 'nickName', 'icon'}
                    assert ok(prefix + '/user/2', token)['id'] == 2
                    info = ok(prefix + '/user/info/1', token)
                    assert info['city'] == 'Hangzhou' and 'createTime' not in info and 'updateTime' not in info
                    assert ok(prefix + '/user/info/2', token) is None
                status, replay = request(gateway_port, '/api/user/login', method='POST', data={'phone': phone, 'code': code})
                assert status == 200 and replay['success'] is False
                assert request(user_port, '/user/me')[0] == 401
                assert request(user_port, '/user/me', identity='1,2')[0] == 400
                assert request(legacy_port, '/user/me', token)[0] == 404

                expired_phone = '13900005678'
                expired_code = issue(expired_phone)
                redis_command(redis_port, 'PEXPIRE', 'login:code:' + expired_phone, 1)
                time.sleep(0.02)
                status, result = request(gateway_port, '/api/user/login', method='POST',
                                         data={'phone': expired_phone, 'code': expired_code})
                assert status == 200 and result['success'] is False
                assert sql("SELECT COUNT(*) FROM hmdp_user_verify.tb_user WHERE phone='%s';" % expired_phone) == '0'

                new_phone = '13900001234'
                new_code = issue(new_phone, '')
                def login_concurrently(_):
                    return request(gateway_port, '/user/login', method='POST', data={'phone': new_phone, 'code': new_code})
                with concurrent.futures.ThreadPoolExecutor(max_workers=3) as executor:
                    responses = list(executor.map(login_concurrently, range(3)))
                successes = [response[1]['data'] for response in responses if response[1]['success']]
                assert len(successes) == 1, responses
                new_token = successes[0]
                new_user = ok('/user/me', new_token)
                assert new_user['id'] >= 1010 and new_user['nickName'].startswith('user_')
                assert sql("SELECT COUNT(*) FROM hmdp_user_verify.tb_user WHERE phone='%s';" % new_phone) == '1'
                second_code = issue(new_phone)
                second_token = ok('/api/user/login', method='POST', data={'phone': new_phone, 'code': second_code})
                assert ok('/user/me', second_token)['id'] == new_user['id']

                assert ok('/api/user/sign/count', token) == 0
                ok('/user/sign', token, 'POST')
                ok('/api/user/sign', token, 'POST')
                assert ok('/user/sign/count', token) == 1
                now = datetime.now(timezone(timedelta(hours=8)))
                assert redis_command(redis_port, 'GETBIT', 'sign:1:' + now.strftime('%Y%m'), now.day - 1) == 1
                assert ok('/user/sign/count', new_token) == 0

                # 真实旧单体调用新服务，且单体数据库没有 tb_user/tb_user_info。
                hot = ok('/api/blog/hot')
                known = {1: '小鱼同学', 2: '可可今天不吃肉', 4: 'user_slxaxy2au9f3tanffaxr', 5: 'user_n0bb8mwwg4'}
                assert hot and all(blog['name'] == known.get(blog['userId'], '已注销用户') for blog in hot)
                redis_command(redis_port, 'ZADD', 'blog:liked:1', 100, 2, 200, 1)
                likes = ok('/blog/likes/1', token)
                assert [user['id'] for user in likes] == [2, 1]
                redis_command(redis_port, 'SADD', 'follows:1', 2, 4)
                redis_command(redis_port, 'SADD', 'follows:5', 2, 4)
                commons = ok('/api/follow/common/5', token)
                assert {user['id'] for user in commons} == {2, 4}
                ok('/blog/hot', token)
                assert 100 <= redis_command(redis_port, 'TTL', 'login:token:' + token) <= 120
                assert sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='hmdp_legacy_verify' AND table_name IN('tb_user','tb_user_info');") == '0'
                status, summaries = request(user_port, '/internal/users/batch?ids=2,1,999')
                assert status == 200 and [user['id'] for user in summaries['data']] == [2, 1]
                assert all(set(user) <= {'id', 'nickName', 'icon'} for user in summaries['data'])
                status, too_many = request(user_port, '/internal/users/batch?ids=' + ','.join(['1'] * 101))
                assert status == 200 and too_many['success'] is False

                ok('/api/user/logout', token, 'POST')
                assert redis_command(redis_port, 'EXISTS', 'login:token:' + token) == 0
                assert request(gateway_port, '/user/me', token)[0] == 401
                ok('/user/logout', new_token, 'POST')
                assert ok('/user/me', second_token)['id'] == new_user['id']
                assert redis_command(redis_port, 'EXISTS', 'login:token:' + second_token) == 1
                stop(user_process)
                assert request(gateway_port, '/api/user/me', second_token)[0] >= 500
                print('PASS: real user login/logout, one-use OTP, new registration, token TTL/renewal,')
                print('      profiles, common identity, sign bitmap, concurrent login and session isolation;')
                print('      real legacy blog/likes/follows query remote profiles with NO local user tables')
                print('Verification logs and isolated databases: ' + str(work))
            except Exception:
                for name in logs:
                    print(name + '.log:\n' + (work / (name + '.log')).read_text(encoding='utf-8', errors='replace')[-7000:])
                raise
            finally:
                stop(gateway_process)
                stop(legacy_process)
                stop(user_process)
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
        for process in [gateway_process, legacy_process, user_process, mysql_process, redis_process]:
            stop(process)


if __name__ == '__main__':
    main()
