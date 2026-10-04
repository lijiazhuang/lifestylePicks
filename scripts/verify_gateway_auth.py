"""Verify the packaged gateway using isolated Redis and HTTP backend processes.

Only Python's standard library is required. No existing Redis database is used.
"""
import argparse
import concurrent.futures
import json
import os
import pathlib
import re
import shutil
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def redis_command(port, *args):
    def read_response(stream):
        line = stream.readline()
        if not line:
            raise RuntimeError('Redis closed the connection')
        kind, value = line[:1], line[1:-2]
        if kind == b'+':
            return value.decode()
        if kind == b'-':
            raise RuntimeError(value.decode())
        if kind == b':':
            return int(value)
        if kind == b'$':
            size = int(value)
            if size == -1:
                return None
            data = stream.read(size)
            assert stream.read(2) == b'\r\n'
            return data.decode()
        if kind == b'*':
            return [read_response(stream) for _ in range(int(value))]
        raise RuntimeError('Unexpected Redis response: ' + repr(line))

    values = [str(arg).encode() for arg in args]
    command = b'*%d\r\n' % len(values)
    command += b''.join(b'$%d\r\n' % len(value) + value + b'\r\n' for value in values)
    with socket.create_connection(('127.0.0.1', port), timeout=2) as connection:
        connection.sendall(command)
        with connection.makefile('rb') as stream:
            return read_response(stream)


class Backend(BaseHTTPRequestHandler):
    calls = []
    lock = threading.Lock()

    def handle_request(self):
        body = self.rfile.read(int(self.headers.get('Content-Length', '0'))).decode('utf-8')
        echo = {'method': self.command, 'path': self.path, 'body': body,
                'userId': self.headers.get('X-User-Id'),
                'identityHeaders': self.headers.get_all('X-User-Id') or [],
                'token': self.headers.get('authorization')}
        with self.lock:
            self.calls.append(echo)
        data = json.dumps({'success': True, 'data': echo}).encode()
        status = 401 if self.path == '/user/me?reject=1' else (201 if self.command == 'POST' else 200)
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        if self.command != 'HEAD':
            self.wfile.write(data)

    do_GET = handle_request
    do_HEAD = handle_request
    do_POST = handle_request
    do_PUT = handle_request
    do_DELETE = handle_request

    def log_message(self, *_):
        pass


def stop(process):
    if process is not None and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=8)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=3)


@contextmanager
def verification_directory(target):
    # 普通目录继承工作区权限，避免 Windows 沙箱中 tempfile 的专用 ACL。
    # 保留日志供复查，目录位于已忽略的 target 下。
    work = target / ('auth-check-' + uuid.uuid4().hex)
    work.mkdir()
    yield work


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default=shutil.which('java'))
    parser.add_argument('--redis-server', default=shutil.which('redis-server'))
    args = parser.parse_args()
    if not args.java or not args.redis_server:
        parser.error('Provide a Java 8 executable and redis-server using the command-line options')
    root = pathlib.Path(__file__).resolve().parents[1]
    target = root / 'lifestylePicks-gateway' / 'target'
    jar = target / 'lifestylePicks-gateway-0.0.1-SNAPSHOT.jar'
    if not jar.exists():
        parser.error('Build the gateway JAR first')
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 0))
        redis_port = probe.getsockname()[1]
    backend = ThreadingHTTPServer(('127.0.0.1', 0), Backend)
    threading.Thread(target=backend.serve_forever, daemon=True).start()
    redis_process = gateway_process = None
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    try:
        with verification_directory(target) as directory:
            work = pathlib.Path(directory)
            with (work / 'redis.log').open('w') as redis_log, (work / 'gateway.log').open('w') as gateway_log:
                try:
                    redis_process = subprocess.Popen([
                        str(pathlib.Path(args.redis_server).resolve()), '--bind', '127.0.0.1',
                        '--port', str(redis_port), '--save', '', '--appendonly', 'no', '--dir', str(work),
                    ], cwd=work, stdout=redis_log, stderr=subprocess.STDOUT, creationflags=flags)
                    deadline = time.monotonic() + 15
                    while True:
                        try:
                            assert redis_command(redis_port, 'PING') == 'PONG'
                            break
                        except OSError:
                            if time.monotonic() > deadline or redis_process.poll() is not None:
                                raise RuntimeError('Temporary Redis failed to start')
                            time.sleep(0.1)
                    for token, user_id in [('alice', 42), ('bob', 84), ('malformed', '-1')]:
                        redis_command(redis_port, 'HMSET', 'login:token:' + token, 'id', user_id, 'nickName', token)
                        redis_command(redis_port, 'EXPIRE', 'login:token:' + token, 45)
                    redis_command(redis_port, 'HSET', 'login:token:missing-id', 'nickName', 'test')
                    redis_command(redis_port, 'EXPIRE', 'login:token:missing-id', 45)
                    redis_command(redis_port, 'HSET', 'login:token:expired', 'id', 99)
                    redis_command(redis_port, 'PEXPIRE', 'login:token:expired', 1)
                    time.sleep(0.02)

                    env = dict(os.environ, REDIS_HOST='127.0.0.1', REDIS_PORT=str(redis_port),
                               REDIS_PASSWORD='', REDIS_DATABASE='0',
                               MONOLITH_URI='http://127.0.0.1:%s' % backend.server_port)
                    # 此脚本只验证鉴权，店铺路由也使用同一模拟上游；真实店铺链路另有验证脚本。
                    env['SHOP_SERVICE_URI'] = env['MONOLITH_URI']
                    env['USER_SERVICE_URI'] = env['MONOLITH_URI']
                    env['CONTENT_SERVICE_URI'] = env['MONOLITH_URI']
                    env['TRADE_SERVICE_URI'] = env['MONOLITH_URI']
                    gateway_process = subprocess.Popen([
                        args.java, '-jar', str(jar), '--server.address=127.0.0.1', '--server.port=0',
                        '--lifestylepicks.auth.token-ttl=120s', '--lifestylepicks.auth.redis-timeout=500ms',
                        '--management.endpoint.health.show-details=always',
                    ], cwd=root, env=env, stdout=gateway_log, stderr=subprocess.STDOUT, creationflags=flags)
                    deadline = time.monotonic() + 45
                    port = None
                    while time.monotonic() < deadline:
                        output = (work / 'gateway.log').read_text(encoding='utf-8', errors='replace')
                        match = re.search(r'Netty started on port\(s\): (\d+)', output)
                        if match:
                            port = int(match.group(1))
                            break
                        if gateway_process.poll() is not None:
                            raise RuntimeError('Gateway failed to start')
                        time.sleep(0.2)
                    assert port, 'Gateway startup timed out'

                    def request(path, token=None, method='GET', body=None, forged=False):
                        headers = {'Content-Type': 'application/json'}
                        if token is not None:
                            headers['authorization'] = token
                        if forged:
                            headers['x-user-id'] = '999'
                        req = urllib.request.Request('http://127.0.0.1:%s%s' % (port, path),
                                                     data=body, headers=headers, method=method)
                        try:
                            response = urllib.request.urlopen(req, timeout=5)
                        except urllib.error.HTTPError as error:
                            response = error
                        with response:
                            content = response.read()
                            return response.status, json.loads(content.decode()) if content else None

                    def denied(path, token=None, method='GET', expected=401):
                        before = len(Backend.calls)
                        status, result = request(path, token, method, forged=True)
                        assert status == expected and result['success'] is False, (path, status, result)
                        assert len(Backend.calls) == before, 'Denied request reached backend'

                    status, result = request('/actuator/health')
                    assert status == 200 and result['status'] == 'UP', result
                    for prefix in ['', '/api']:
                        status, result = request(prefix + '/shop/1?tag=a&tag=b', forged=True)
                        assert status == 200 and result['data']['userId'] is None, result
                        assert result['data']['path'] == '/shop/1?tag=a&tag=b', result
                        denied(prefix + '/user/me')
                        denied(prefix + '/user/me', 'expired')
                        denied(prefix + '/shop', method='PUT')
                        denied(prefix + '/voucher/seckill', method='POST')
                        denied(prefix + '/upload/blog/delete')
                        denied(prefix + '/voucher-order/seckill/1', method='POST')
                        denied(prefix + '/shop/not-a-number')
                        denied(prefix + '/user/login')  # Only POST login is public.
                        payload = b'{"phone":"12345678901","code":"test"}'
                        status, result = request(prefix + '/user/login', method='POST', body=payload, forged=True)
                        assert status == 201 and result['data']['body'] == payload.decode(), result
                        assert result['data']['userId'] is None, result
                        for path in ['/user/me', '/shop/1']:
                            status, result = request(prefix + path, 'alice', forged=True)
                            assert status == 200 and result['data']['identityHeaders'] == ['42'], result
                            assert result['data']['token'] == 'alice', result
                        status, result = request(prefix + '/voucher-order/seckill/1', 'alice', 'POST', b'{}')
                        assert status == 201 and result['data']['userId'] == '42', result
                    assert redis_command(redis_port, 'TTL', 'login:token:alice') >= 115
                    assert redis_command(redis_port, 'EXISTS', 'login:token:expired') == 0
                    for token in ['malformed', 'missing-id']:
                        denied('/api/user/me', token)
                        assert redis_command(redis_port, 'TTL', 'login:token:' + token) <= 45
                    status, result = request('/api/blog/hot', 'expired', forged=True)
                    assert status == 200 and result['data']['userId'] is None, result
                    denied('/apix/user/login', method='POST')
                    denied('/api/api/user/login', method='POST')
                    status, result = request('/api/user/me?reject=1', 'alice')
                    assert status == 401 and result['data']['userId'] == '42', result

                    def concurrent_request(index):
                        token, expected = ('alice', '42') if index % 2 == 0 else ('bob', '84')
                        status, result = request('/api/user/me', token, forged=True)
                        assert status == 200 and result['data']['userId'] == expected, result
                    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
                        list(executor.map(concurrent_request, range(12)))

                    stop(redis_process)
                    denied('/api/user/me', 'alice', expected=503)
                    denied('/api/user/me')
                    status, result = request('/api/blog/hot', 'alice', forged=True)
                    assert status == 200 and result['data']['userId'] is None, result
                    status, result = request('/shop/1', forged=True)
                    assert status == 200 and result['data']['userId'] is None, result
                    print('PASS: real Redis renewal, expired/malformed tokens, 401/503, method/path rules,')
                    print('      both URL prefixes, identity-header spoofing, public optional identity,')
                    print('      concurrent user isolation, request forwarding and Redis outage handling')
                except Exception:
                    stop(gateway_process)
                    stop(redis_process)
                    for name in ['redis.log', 'gateway.log']:
                        print(name + ':\n' + (work / name).read_text(encoding='utf-8', errors='replace')[-7000:])
                    raise
                finally:
                    stop(gateway_process)
                    stop(redis_process)
    finally:
        backend.shutdown()
        backend.server_close()
        stop(gateway_process)
        stop(redis_process)


if __name__ == '__main__':
    main()
