"""Exercise all four services without the old monolith, using stdlib only."""
import argparse
import concurrent.futures
import base64
import struct
import zlib
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
    parser.add_argument('--rocketmq-name-server', required=True, help='Use an existing broker; this script never installs RocketMQ')
    parser.add_argument('--rocketmq-topic', required=True, help='Dedicated existing test Topic, separate from production')
    args = parser.parse_args()
    if not all([args.java, args.redis_server, args.mysqld, args.mysql]):
        parser.error('Provide Java 8, redis-server, mysqld and mysql executable paths')
    root = Path(__file__).resolve().parents[1]
    target = root / 'lifestylePicks-trade-service' / 'target'
    jars = {name: root / ('lifestylePicks-' + name + '-service') / 'target' / ('lifestylePicks-' + name + '-service-0.0.1-SNAPSHOT.jar') for name in ['user','shop','content','trade']}
    gateway_jar = root / 'lifestylePicks-gateway/target/lifestylePicks-gateway-0.0.1-SNAPSHOT.jar'
    if not gateway_jar.exists() or not all(path.exists() for path in jars.values()):
        parser.error('Build the reactor first')
    mysql_port, redis_port = free_port(), free_port()
    mysql_base = Path(args.mysqld).resolve().parent.parent
    backend = ThreadingHTTPServer(('127.0.0.1', 0), Backend)
    threading.Thread(target=backend.serve_forever, daemon=True).start()
    mysql_process = redis_process = None
    java_processes = []
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    try:
        with verification_directory(target) as work, ExitStack() as stack:
            data_dir = work / 'mysql-data'
            tmp_dir = work / 'mysql-tmp'
            data_dir.mkdir()
            tmp_dir.mkdir()
            logs = {name: stack.enter_context((work / (name + '.log')).open('w'))
                    for name in ['mysql', 'redis', 'user', 'shop', 'content', 'trade', 'trade2', 'trade3', 'gateway']}

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

                java_processes.append(process)

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
                for name in jars:
                    fixture=(root/('lifestylePicks-'+name+'-service')/'src/main/resources/db'/(name+'.sql')).read_text(encoding='utf-8')
                    sql('CREATE DATABASE verify_'+name+' CHARACTER SET utf8mb4; USE verify_'+name+';\n'+fixture)
                redis_process=launch([args.redis_server,'--bind','127.0.0.1','--port',str(redis_port),
                                      '--save','','--appendonly','no','--dir',str(work)],'redis')
                wait_until(lambda: redis_command(redis_port,'PING')=='PONG',15)
                env=dict(os.environ,REDIS_HOST='127.0.0.1',REDIS_PORT=str(redis_port),REDIS_PASSWORD='',
                         REDIS_DATABASE='0',TOKEN_TTL='120s',USER_LOG_CODE='false',SHOP_GEO_INITIALIZE='true',
                         CONTENT_UPLOAD_DIR=str(work/'uploads'),TRADE_MQ_ENABLED='true',ROCKETMQ_NAME_SERVER=args.rocketmq_name_server,SECKILL_ORDER_TOPIC=args.rocketmq_topic,
                         ROCKETMQ_PRODUCER_GROUP='seckill_verify_producer_'+work.name,ROCKETMQ_CONSUMER_GROUP='seckill_verify_consumer_'+work.name)
                for name in jars:
                    env[name.upper()+'_DB_URL']='jdbc:mysql://127.0.0.1:%s/verify_%s?useSSL=false&serverTimezone=UTC&characterEncoding=utf8'%(mysql_port,name)
                    env[name.upper()+'_DB_USERNAME']='root';env[name.upper()+'_DB_PASSWORD']=''
                _,user_port=start_java(jars['user'],'user',env)
                env['USER_SERVICE_URI']='http://127.0.0.1:%s'%user_port
                _,shop_port=start_java(jars['shop'],'shop',env)
                _,content_port=start_java(jars['content'],'content',env)
                _,trade_port=start_java(jars['trade'],'trade',env)
                env['SHOP_SERVICE_URI']='http://127.0.0.1:%s'%shop_port
                env['CONTENT_SERVICE_URI']='http://127.0.0.1:%s'%content_port
                env['TRADE_SERVICE_URI']='http://127.0.0.1:%s'%trade_port
                # 故意指向不存在的旧后端，正常业务完全不依赖单体。
                env['MONOLITH_URI']='http://127.0.0.1:9'
                _,gateway_port=start_java(gateway_jar,'gateway',env)

                def ok(path,token=None,method='GET',data=None):
                    status,result=request(gateway_port,path,token,method,data)
                    assert status==200 and result.get('success') is True,(path,status,result)
                    return result.get('data')

                def login(phone):
                    ok('/api/user/code?phone='+phone,method='POST')
                    code=redis_command(redis_port,'GET','login:code:'+phone)
                    return ok('/user/login',method='POST',data={'phone':phone,'code':code})

                first=login('13686869696');second=login('13838411438');fourth=login('13456789011')
                assert ok('/api/user/me',first)['id']==1
                assert ok('/shop/1')['id']==1
                hot=ok('/api/blog/hot');assert hot and all('name' in blog for blog in hot)
                assert request(gateway_port,'/api/blog',method='POST',data={},identity='999')[0]==401
                ok('/follow/1/true',second,'PUT');ok('/api/follow/1/true',second,'PUT')
                assert sql('SELECT COUNT(*) FROM verify_content.tb_follow WHERE user_id=2 AND follow_user_id=1;')=='1'
                ok('/follow/1/true',fourth,'PUT')
                assert ok('/follow/or/not/1',second) is True
                assert {user['id'] for user in ok('/follow/common/4',second)}=={1}
                blog_id=ok('/api/blog',first,'POST',{'shopId':1,'userId':999,'title':'Microservice Post','images':'/imgs/test.png','content':'content service test'})
                assert sql('SELECT user_id FROM verify_content.tb_blog WHERE id=%s;'%blog_id)=='1'
                feed=ok('/blog/of/follow?lastId=9223372036854775807&offset=0',second)
                assert any(blog['id']==blog_id for blog in feed['list'])
                ok('/api/blog/like/'+str(blog_id),second,'PUT')
                assert ok('/blog/'+str(blog_id),second)['isLike'] is True
                assert [user['id'] for user in ok('/blog/likes/'+str(blog_id),first)]==[2]
                ok('/blog/like/'+str(blog_id),second,'PUT')
                assert ok('/blog/'+str(blog_id),second)['liked']==0
                comment_id=ok('/blog-comments',second,'POST',{'blogId':blog_id,'content':'comment'})
                assert any(comment['id']==comment_id for comment in ok('/api/blog-comments/of/blog?blogId='+str(blog_id),first))
                status,result=request(gateway_port,'/blog-comments/'+str(comment_id),first,'DELETE')
                assert status==200 and result['success'] is False
                ok('/api/blog-comments/'+str(comment_id),second,'DELETE')

                def png_chunk(kind,payload):
                    return struct.pack('!I',len(payload))+kind+payload+struct.pack('!I',zlib.crc32(kind+payload)&0xffffffff)
                image=(b'\x89PNG\r\n\x1a\n'+png_chunk(b'IHDR',struct.pack('!IIBBBBB',1,1,8,2,0,0,0))
                       +png_chunk(b'IDAT',zlib.compress(b'\x00\xff\x00\x00'))+png_chunk(b'IEND',b''))
                boundary='VerifyImageBoundary'
                multipart=(('--'+boundary+'\r\nContent-Disposition: form-data; name="file"; filename="test.png"\r\nContent-Type: image/png\r\n\r\n').encode()+image+('\r\n--'+boundary+'--\r\n').encode())
                upload=urllib.request.Request('http://127.0.0.1:%s/api/upload/blog'%gateway_port,data=multipart,
                                              headers={'authorization':first,'Content-Type':'multipart/form-data; boundary='+boundary})
                with urllib.request.urlopen(upload,timeout=8) as response:
                    uploaded=json.loads(response.read().decode());assert uploaded['success'] is True,uploaded
                name=uploaded['data']
                with urllib.request.urlopen('http://127.0.0.1:%s/imgs%s'%(gateway_port,name),timeout=8) as response:
                    assert response.read()==image
                status,result=request(gateway_port,'/upload/blog/delete?name=/imgs'+name,second)
                assert status==200 and result['success'] is False
                ok('/api/upload/blog/delete?name=/imgs'+name,first)
                status,result=request(gateway_port,'/upload/blog/delete?name=../../pom.xml',first)
                assert status==200 and result['success'] is False

                assert request(gateway_port,'/voucher/seckill',method='POST',data={},identity='999')[0]==401
                now=datetime.now(timezone(timedelta(hours=8))).replace(tzinfo=None)
                def coupon(stock,start=-5,end=60):
                    return ok('/api/voucher/seckill',first,'POST',{'shopId':1,'title':'Flash','subTitle':'test','rules':'test',
                              'payValue':1000,'actualValue':2000,'stock':stock,
                              'beginTime':(now+timedelta(minutes=start)).isoformat(),
                              'endTime':(now+timedelta(minutes=end)).isoformat()})
                future=coupon(1,start=10,end=20)
                expired=coupon(1,start=-20,end=-10)
                for vid in [future,expired,999999]:
                    status,result=request(gateway_port,'/voucher-order/seckill/'+str(vid),first,'POST')
                    assert status==200 and result['success'] is False,result
                sale=coupon(2)
                assert any(voucher['id']==sale for voucher in ok('/voucher/list/1'))
                buyers=[first,second,fourth]+[login('1390000%04d'%number) for number in range(5)]
                def buy(token):return token,request(gateway_port,'/api/voucher-order/seckill/'+str(sale),token,'POST')
                with concurrent.futures.ThreadPoolExecutor(max_workers=8) as executor:
                    responses=list(executor.map(buy,buyers))
                winners=[(token,response[1]['data']) for token,response in responses if response[1]['success']]
                assert len(winners)==2,responses
                assert all(isinstance(order_id,str) for _,order_id in winners)
                wait_until(lambda:sql('SELECT COUNT(*) FROM verify_trade.tb_voucher_order WHERE voucher_id=%s;'%sale)=='2',40)
                assert redis_command(redis_port,'GET','seckill:stock:'+str(sale))=='0'
                assert sql('SELECT stock FROM verify_trade.tb_seckill_voucher WHERE voucher_id=%s;'%sale)=='0'
                for token,order_id in winners:
                    order=ok('/api/voucher-order/'+order_id,token);assert order['id']==order_id
                loser=next(token for token in buyers if token not in [winner[0] for winner in winners])
                status,result=request(gateway_port,'/voucher-order/'+winners[0][1],loser)
                assert status==200 and result['success'] is False
                rejected=coupon(1)
                sql('UPDATE verify_trade.tb_seckill_voucher SET stock=0 WHERE voucher_id=%s;'%rejected)
                rejected_id=ok('/voucher-order/seckill/'+str(rejected),first,'POST')
                wait_until(lambda:redis_command(redis_port,'HGET','seckill:status:'+rejected_id,'state')=='FAILED',20)
                assert ok('/voucher-order/'+rejected_id,first)['state']=='FAILED'
                assert redis_command(redis_port,'GET','seckill:stock:'+str(rejected))=='0'
                assert redis_command(redis_port,'SISMEMBER','seckill:order:'+str(rejected),1)==0
                assert sql('SELECT COUNT(*) FROM verify_trade.tb_voucher_order WHERE voucher_id=%s;'%rejected)=='0'
                assert sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='verify_content' AND table_name='tb_user';")=='0'
                assert sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='verify_trade' AND table_name IN('tb_user','tb_shop');")=='0'
                print('PASS: all four services and gateway work without the monolith, using four isolated MySQL schemas')
                print('      content profiles, follow/feed/likes/comments/uploads, trade time windows and concurrent stock,')
                print('      existing RocketMQ delivery and permanent inventory rejection')
                print('Verification logs and databases: '+str(work))
            except Exception:
                for name in logs:
                    print(name+'.log:\n'+(work/(name+'.log')).read_text(encoding='utf-8',errors='replace')[-5000:])
                raise
            finally:
                for process in reversed(java_processes):stop(process)
                if mysql_process is not None and mysql_process.poll() is None:
                    try:sql('SHUTDOWN;',False);mysql_process.wait(timeout=10)
                    except (subprocess.TimeoutExpired,OSError):pass
                stop(mysql_process);stop(redis_process)
    finally:
        backend.shutdown();backend.server_close()
        for process in reversed(java_processes):stop(process)
        stop(mysql_process);stop(redis_process)


if __name__=='__main__':main()
