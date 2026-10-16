"""Prepare real unused users, temporary Redis sessions and a one-shot JMeter plan.

Only standard-library dependencies are needed. This script does not place orders,
reset stock, remove buyers or change the activity window.
"""
import argparse
import csv
from datetime import datetime, timezone, timedelta
import getpass
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import time
import urllib.request
import uuid
import xml.etree.ElementTree as ET


class Redis:
    def __init__(self, host, port, database, password):
        self.sock = socket.create_connection((host, port), timeout=5)
        self.stream = self.sock.makefile('rb')
        if password:
            self.command('AUTH', password)
        self.command('SELECT', database)

    def read(self):
        line = self.stream.readline()
        if not line:
            raise RuntimeError('Redis connection closed')
        kind, value = line[:1], line[1:-2]
        if kind == b'+': return value.decode('utf-8')
        if kind == b'-': raise RuntimeError(value.decode('utf-8'))
        if kind == b':': return int(value)
        if kind == b'$':
            size = int(value)
            if size == -1: return None
            result = self.stream.read(size)
            if self.stream.read(2) != b'\r\n': raise RuntimeError('Invalid Redis reply')
            return result.decode('utf-8')
        if kind == b'*':
            size = int(value)
            return None if size == -1 else [self.read() for _ in range(size)]
        raise RuntimeError('Unexpected Redis response')

    def command(self, *values):
        parts = [str(value).encode('utf-8') for value in values]
        request = b'*%d\r\n' % len(parts)
        request += b''.join(b'$%d\r\n' % len(part) + part + b'\r\n' for part in parts)
        self.sock.sendall(request)
        return self.read()

    def close(self):
        self.stream.close()
        self.sock.close()


def prop(parent, name, value, kind='stringProp'):
    ET.SubElement(parent, kind, name=name).text = str(value)


def node(parent, tag, gui, name):
    element = ET.SubElement(parent, tag, guiclass=gui, testclass=tag, testname=name, enabled='true')
    tree = ET.SubElement(parent, 'hashTree')
    return element, tree


def build_plan(path, voucher_id, count, csv_name):
    root = ET.Element('jmeterTestPlan', version='1.2', properties='5.0', jmeter='5.6.3')
    tree = ET.SubElement(root, 'hashTree')
    plan, plan_tree = node(tree, 'TestPlan', 'TestPlanGui', 'Voucher seckill one-shot burst')
    prop(plan, 'TestPlan.comments', 'Each CSV user sends one request. Admission and persistence are separate.')
    prop(plan, 'TestPlan.functional_mode', 'false', 'boolProp')
    prop(plan, 'TestPlan.tearDown_on_shutdown', 'true', 'boolProp')
    variables = ET.SubElement(plan, 'elementProp', name='TestPlan.user_defined_variables', elementType='Arguments',
                              guiclass='ArgumentsPanel', testclass='Arguments', testname='Variables', enabled='true')
    arguments = ET.SubElement(variables, 'collectionProp', name='Arguments.arguments')
    argument = ET.SubElement(arguments, 'elementProp', name='voucherId', elementType='Argument')
    prop(argument, 'Argument.name', 'voucherId')
    prop(argument, 'Argument.value', '${__P(voucherId,%s)}' % voucher_id)
    prop(argument, 'Argument.metadata', '=')

    group, group_tree = node(plan_tree, 'ThreadGroup', 'ThreadGroupGui', 'Concurrent users - one request each')
    prop(group, 'ThreadGroup.on_sample_error', 'continue')
    loop = ET.SubElement(group, 'elementProp', name='ThreadGroup.main_controller', elementType='LoopController',
                         guiclass='LoopControlPanel', testclass='LoopController', testname='Once', enabled='true')
    prop(loop, 'LoopController.continue_forever', 'false', 'boolProp')
    prop(loop, 'LoopController.loops', '1')
    prop(group, 'ThreadGroup.num_threads', '${__P(threads,%s)}' % count)
    prop(group, 'ThreadGroup.ramp_time', '${__P(rampup,1)}')
    prop(group, 'ThreadGroup.scheduler', 'false', 'boolProp')
    prop(group, 'ThreadGroup.same_user_on_next_iteration', 'true', 'boolProp')

    dataset, _ = node(group_tree, 'CSVDataSet', 'TestBeanGUI', 'Unique users and tokens')
    prop(dataset, 'filename', '${__P(csvFile,%s)}' % csv_name)
    prop(dataset, 'fileEncoding', 'UTF-8')
    prop(dataset, 'variableNames', 'userId,token')
    prop(dataset, 'ignoreFirstLine', 'true', 'boolProp')
    prop(dataset, 'delimiter', ',')
    prop(dataset, 'quotedData', 'false', 'boolProp')
    prop(dataset, 'recycle', 'false', 'boolProp')
    prop(dataset, 'stopThread', 'true', 'boolProp')
    prop(dataset, 'shareMode', 'shareMode.all')

    headers, _ = node(group_tree, 'HeaderManager', 'HeaderPanel', 'Redis Token authorization')
    collection = ET.SubElement(headers, 'collectionProp', name='HeaderManager.headers')
    header = ET.SubElement(collection, 'elementProp', name='', elementType='Header')
    prop(header, 'Header.name', 'authorization')
    prop(header, 'Header.value', '${token}')

    request, request_tree = node(group_tree, 'HTTPSamplerProxy', 'HttpTestSampleGui', 'Seckill admission')
    args = ET.SubElement(request, 'elementProp', name='HTTPsampler.Arguments', elementType='Arguments',
                         guiclass='HTTPArgumentsPanel', testclass='Arguments', testname='No body', enabled='true')
    ET.SubElement(args, 'collectionProp', name='Arguments.arguments')
    for name, value in {
        'HTTPSampler.domain': '${__P(host,127.0.0.1)}',
        'HTTPSampler.port': '${__P(port,10010)}',
        'HTTPSampler.protocol': 'http',
        'HTTPSampler.path': '/api/voucher-order/seckill/${voucherId}',
        'HTTPSampler.method': 'POST',
        'HTTPSampler.implementation': 'HttpClient4',
        'HTTPSampler.connect_timeout': '5000',
        'HTTPSampler.response_timeout': '10000',
    }.items(): prop(request, name, value)
    for name, value in {
        'HTTPSampler.follow_redirects': 'false',
        'HTTPSampler.auto_redirects': 'false',
        'HTTPSampler.use_keepalive': 'true',
        'HTTPSampler.DO_MULTIPART_POST': 'false',
    }.items(): prop(request, name, value, 'boolProp')

    timer, _ = node(request_tree, 'SyncTimer', 'TestBeanGUI', 'Release all group threads together')
    prop(timer, 'groupSize', '0', 'intProp')
    prop(timer, 'timeoutInMs', '10000', 'longProp')

    assertion, _ = node(request_tree, 'JSR223Assertion', 'TestBeanGUI', 'Check business result and retain order ID')
    prop(assertion, 'scriptLanguage', 'groovy')
    prop(assertion, 'cacheKey', 'true')
    prop(assertion, 'filename', '')
    prop(assertion, 'parameters', '')
    prop(assertion, 'script', '''import groovy.json.JsonSlurper
vars.put('orderId', '')
vars.put('businessResult', 'UNEXPECTED_ERROR')
def uid = vars.get('userId')
try {
    if (prev.getResponseCode() != '200') {
        vars.put('businessResult', 'HTTP_ERROR')
        AssertionResult.setFailure(true)
        AssertionResult.setFailureMessage('HTTP failure: ' + prev.getResponseCode())
        prev.setResponseMessage('HTTP_ERROR|userId=' + uid)
        return
    }
    def result = new JsonSlurper().parseText(new String(prev.getResponseData(), 'UTF-8'))
    if (result.success == true && result.data != null && result.data.toString() ==~ /[1-9][0-9]*/) {
        def id = result.data.toString()
        vars.put('orderId', id)
        vars.put('businessResult', 'ACCEPTED')
        prev.setResponseMessage('ACCEPTED|userId=' + uid + '|orderId=' + id)
    } else {
        def reason = result.errorMsg ?: 'Invalid admission response'
        def category = reason == '库存不足' ? 'STOCK_OUT' : (reason == '不能重复下单' ? 'DUPLICATE' : 'BUSINESS_ERROR')
        vars.put('businessResult', category)
        AssertionResult.setFailure(true)
        AssertionResult.setFailureMessage(category + ': ' + reason)
        prev.setResponseMessage(category + '|userId=' + uid + '|reason=' + reason)
    }
} catch (Exception failure) {
    AssertionResult.setFailure(true)
    AssertionResult.setFailureMessage('Invalid JSON or business response: ' + failure.getClass().getSimpleName())
    prev.setResponseMessage('UNEXPECTED_ERROR|userId=' + uid)
}
''')

    # Aggregate Report is inexpensive and also saves a CSV JTL in GUI mode.
    collector, _ = node(plan_tree, 'ResultCollector', 'StatVisualizer', 'Aggregate Report and CSV results')
    prop(collector, 'ResultCollector.error_logging', 'false', 'boolProp')
    obj = ET.SubElement(collector, 'objProp')
    ET.SubElement(obj, 'name').text = 'saveConfig'
    save = ET.SubElement(obj, 'value', {'class': 'SampleSaveConfiguration'})
    for name in ['time', 'latency', 'timestamp', 'success', 'label', 'code', 'message', 'threadName',
                 'assertions', 'bytes', 'sentBytes', 'url', 'threadCounts', 'idleTime', 'connectTime', 'fieldNames',
                 'saveAssertionResultsFailureMessage', 'printMilliseconds']:
        ET.SubElement(save, name).text = 'true'
    for name in ['dataType', 'encoding', 'xml', 'responseData', 'samplerData', 'responseHeaders', 'requestHeaders',
                 'responseDataOnError']:
        ET.SubElement(save, name).text = 'false'
    ET.SubElement(save, 'assertionsResultsToSave').text = '0'
    prop(collector, 'filename', '${__P(guiResults,%s)}' % (path.parent.resolve() / 'results.csv').as_posix())
    ET.indent(root, space='  ')
    ET.ElementTree(root).write(path, encoding='utf-8', xml_declaration=True)


def mysql_query(args, password, statement):
    env = os.environ.copy()
    env['MYSQL_PWD'] = password
    result = subprocess.run([args.mysql, '--no-defaults', '--protocol=TCP', '--host=' + args.db_host,
                             '--port=' + str(args.db_port), '--user=' + args.db_user,
                             '--default-character-set=utf8mb4', '--batch', '--skip-column-names',
                             '--execute=' + statement], env=env, text=True, encoding='utf-8', capture_output=True,
                            timeout=15, creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
    if result.returncode:
        raise RuntimeError('MySQL query failed: ' + result.stderr.strip())
    return [line.split('\t') for line in result.stdout.splitlines() if line]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--voucher-id', type=int, default=11)
    parser.add_argument('--count', type=int, default=100)
    parser.add_argument('--mysql', default=shutil.which('mysql') or r'C:\Program Files\mysql8\mysql-8.0.26-winx64\bin\mysql.exe')
    parser.add_argument('--db-host', default='127.0.0.1')
    parser.add_argument('--db-port', type=int, default=3306)
    parser.add_argument('--db-user', default='root')
    parser.add_argument('--user-database', default='hmdp')
    parser.add_argument('--trade-database', default='hmdp')
    parser.add_argument('--redis-host', default='127.0.0.1')
    parser.add_argument('--redis-port', type=int, default=6379)
    parser.add_argument('--redis-database', type=int, default=0)
    parser.add_argument('--redis-password', action='store_true', help='Prompt for Redis password')
    parser.add_argument('--gateway-url', default='http://127.0.0.1:10010')
    parser.add_argument('--token-ttl', type=int, default=7200)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if args.voucher_id <= 0 or args.count <= 0 or args.token_ttl <= 0:
        parser.error('Voucher ID, count and TTL must be positive')
    for database in [args.user_database, args.trade_database]:
        if not re.fullmatch(r'[A-Za-z0-9_]+', database): parser.error('Invalid database name')
    if not Path(args.mysql).is_file(): parser.error('Provide --mysql executable path')
    root = Path(__file__).resolve().parents[1]
    folder = args.output or root / 'target' / ('jmeter-voucher-' + str(args.voucher_id))
    if folder.exists(): parser.error('Output already exists; use --output to prepare a new run without overwriting it')
    password = getpass.getpass('MySQL password: ')
    redis_password = getpass.getpass('Redis password: ') if args.redis_password else ''
    trade, users, vid = args.trade_database, args.user_database, args.voucher_id
    voucher = mysql_query(args, password,
        'SELECT stock,begin_time,end_time FROM `%s`.tb_seckill_voucher WHERE voucher_id=%d;' % (trade, vid))
    if not voucher: raise RuntimeError('Seckill voucher does not exist in MySQL')
    db_stock = int(voucher[0][0])
    old_count = int(mysql_query(args, password,
        'SELECT COUNT(*) FROM `%s`.tb_voucher_order WHERE voucher_id=%d;' % (trade, vid))[0][0])
    user_ids = [int(row[0]) for row in mysql_query(args, password,
        'SELECT u.id FROM `%s`.tb_user u WHERE NOT EXISTS '
        '(SELECT 1 FROM `%s`.tb_voucher_order o WHERE o.user_id=u.id AND o.voucher_id=%d) ORDER BY u.id;' % (users, trade, vid))]
    redis = Redis(args.redis_host, args.redis_port, args.redis_database, redis_password)
    created = []
    try:
        stock = redis.command('GET', 'seckill:stock:' + str(vid))
        meta_list = redis.command('HGETALL', 'seckill:meta:' + str(vid))
        meta = dict(zip(meta_list[::2], meta_list[1::2]))
        now = int(time.time() * 1000)
        if stock is None or not {'begin', 'end'} <= meta.keys(): raise RuntimeError('Redis seckill data is missing')
        if not int(meta['begin']) <= now <= int(meta['end']): raise RuntimeError('Activity is not currently open')
        stock = int(stock)
        if stock <= 0: raise RuntimeError('Stock is exhausted; no successful-admission load test can be prepared')
        if stock != db_stock: raise RuntimeError('Redis and MySQL stock differ; wait for pending orders before preparing a baseline')
        candidates = []
        for uid in user_ids:
            if not redis.command('SISMEMBER', 'seckill:order:' + str(vid), uid):
                candidates.append(uid)
                if len(candidates) == args.count: break
        if len(candidates) < args.count: raise RuntimeError('Not enough distinct existing users who have not reserved/bought this voucher')
        baseline = {'prepared_at': datetime.now(timezone(timedelta(hours=8))).isoformat(), 'voucher_id': vid,
                    'threads': args.count, 'redis_stock_before': stock, 'mysql_stock_before': db_stock,
                    'mysql_orders_before': old_count, 'redis_buyers_before': redis.command('SCARD', 'seckill:order:' + str(vid)),
                    'activity_begin_ms': int(meta['begin']), 'activity_end_ms': int(meta['end']),
                    'max_expected_acceptances': min(args.count, stock),
                    'expected_stock_out_if_no_other_traffic': max(0, args.count - stock),
                    'gateway_url': args.gateway_url}
        rows = []
        for uid in candidates:
            token = uuid.uuid4().hex
            key = 'login:token:' + token
            redis.command('MULTI')
            redis.command('HSET', key, 'id', uid)
            redis.command('EXPIRE', key, args.token_ttl)
            replies = redis.command('EXEC')
            created.append(key)
            if replies is None or replies[1] != 1: raise RuntimeError('Test session creation failed')
            rows.append((uid, token))
        # A read-only authenticated endpoint checks that the gateway accepts these sessions.
        request = urllib.request.Request(args.gateway_url.rstrip('/') + '/api/user/me',
                                         headers={'authorization': rows[0][1]})
        result = json.load(urllib.request.urlopen(request, timeout=5))
        if result.get('success') is not True or result.get('data', {}).get('id') != rows[0][0]:
            raise RuntimeError('Gateway identity smoke check failed')
        folder.mkdir(parents=True)
        csv_path = folder / ('users-%d.csv' % args.count)
        with csv_path.open('w', encoding='utf-8', newline='') as stream:
            writer = csv.writer(stream)
            writer.writerow(['userId', 'token'])
            writer.writerows(rows)
        plan = folder / ('voucher-%d-%d.jmx' % (vid, args.count))
        build_plan(plan, vid, args.count, csv_path.name)
        (folder / 'baseline.json').write_text(json.dumps(baseline, ensure_ascii=False, indent=2), encoding='utf-8')
        (folder / 'README.md').write_text('''# 秒杀并发测试输入

同目录包含用户 CSV、JMeter JMX 和测试前基线。每个不同用户只请求一次，不自动重置库存或旧订单。

1. JMeter 打开 JMX。CSV 已设为 userId,token、跳过第一行、所有线程共享、不循环、EOF 停止线程。
2. 请求为 POST /api/voucher-order/seckill/${voucherId}，Header 为 authorization: ${token}。
3. 默认通过本机网关 10010。线程组运行一次，同步定时器等待线程一起发出请求，最长等 10 秒。
4. 同一次测试不要在 GUI 和命令行重复执行。库存和购买记录会被真实消耗；再次测试需要新的有效基线。
5. CSV 含临时登录令牌，放在被 Git 忽略的 target 下，不上传 GitHub。使用后可通过 Redis 删除这些 CSV 对应的 login:token:{token}；不要清理库存、买家或订单状态。

JSON 业务断言会把库存不足标记为失败。它是业务拒绝，和 HTTP/系统异常分别统计。
受理成功的 orderId 保存在结果文件的 responseMessage 中。JMeter 吞吐只表示 HTTP 请求吞吐，异步订单还需核对。
默认生成 results.csv（GUI 聚合报告），命令行可用 -JguiResults= 指定为空，并用 -l 单独保存结果。
''', encoding='utf-8')
        print(json.dumps({'prepared_users': len(rows), 'voucher_id': vid, 'stock_before': stock,
                          'expected_max_acceptances': min(stock, len(rows)), 'csv': str(csv_path),
                          'jmx': str(plan), 'baseline': str(folder / 'baseline.json'),
                          'identity_smoke_check': 'passed', 'orders_placed_by_preparation': 0}, ensure_ascii=True))
    except Exception:
        for key in created:
            try: redis.command('DEL', key)
            except Exception: pass
        raise
    finally:
        redis.close()


if __name__ == '__main__':
    main()
