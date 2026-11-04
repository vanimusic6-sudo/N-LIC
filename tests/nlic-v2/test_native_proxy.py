#!/usr/bin/env python3
"""Run authenticated local SOCKS5 TCP/UDP smoke tests; no Internet needed."""
import os
import signal
import socket
import subprocess
import threading
import time

exe=os.environ["NLIC_TEST_PROXY"]
PASSWORD="a"*48

def recv_exact(sock,count):
    out=b""
    while len(out)<count:
        part=sock.recv(count-len(out))
        if not part: raise AssertionError("unexpected EOF")
        out+=part
    return out

def listen_tcp():
    s=socket.socket()
    s.bind(("127.0.0.1",0));s.listen(4);s.settimeout(4)
    port=s.getsockname()[1]
    def run():
        try:
            conn,_=s.accept()
            with conn:
                data=conn.recv(1024)
                conn.sendall(data)
        finally:
            s.close()
    t=threading.Thread(target=run,daemon=True);t.start()
    return port,t

def open_proxy(port,passphrase=PASSWORD):
    s=socket.create_connection(("127.0.0.1",port),timeout=2);s.settimeout(2)
    s.sendall(b"\x05\x01\x02")
    assert recv_exact(s,2)==b"\x05\x02"
    user=b"nlic"; pwd=passphrase.encode()
    s.sendall(b"\x01"+bytes((len(user),))+user+bytes((len(pwd),))+pwd)
    return s,recv_exact(s,2)

def cmd(s,command,port):
    req=b"\x05"+bytes((command,))+b"\x00\x01"+socket.inet_aton("127.0.0.1")
    req+=port.to_bytes(2,"big")
    s.sendall(req);reply=recv_exact(s,10)
    assert reply[:2]==b"\x05\x00",reply
    return reply

def main():
    probe=socket.socket()
    probe.bind(("127.0.0.1",0))
    proxy_port=probe.getsockname()[1]
    probe.close()
    task=subprocess.Popen([exe,str(proxy_port),"nlic",PASSWORD],
        stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    try:
        for _ in range(100):
            if task.poll() is not None: raise AssertionError("proxy exited")
            try:
                socket.create_connection(("127.0.0.1",proxy_port),timeout=.1).close()
                break
            except OSError:
                time.sleep(.03)
        else: raise AssertionError("proxy did not start")
        bad,reply=open_proxy(proxy_port,"b"*48)
        assert reply==b"\x01\x01",reply
        bad.close()
        port,thread=listen_tcp()
        good,reply=open_proxy(proxy_port)
        assert reply==b"\x01\x00",reply
        cmd(good,1,port)
        good.sendall(b"TCP-ECHO")
        assert recv_exact(good,8)==b"TCP-ECHO"
        good.close();thread.join(timeout=4)
        udp=socket.socket(socket.AF_INET,socket.SOCK_DGRAM)
        udp.bind(("127.0.0.1",0));udp.settimeout(3)
        def server():
            data,from_addr=udp.recvfrom(2048)
            udp.sendto(data,from_addr)
        thread=threading.Thread(target=server,daemon=True)
        # For UDP use a separate socket for remote echo service:
        echo=socket.socket(socket.AF_INET,socket.SOCK_DGRAM)
        echo.bind(("127.0.0.1",0));echo.settimeout(3)
        def echo_once():
            data,peer=echo.recvfrom(2048)
            echo.sendto(data,peer)
        responder=threading.Thread(target=echo_once,daemon=True)
        responder.start()
        control,reply=open_proxy(proxy_port)
        assert reply==b"\x01\x00"
        association=cmd(control,3,0)
        relay_port=int.from_bytes(association[8:10],"big")
        addr=socket.inet_aton("127.0.0.1")
        payload=b"UDP-ECHO"
        packet=b"\x00\x00\x00\x01"+addr+echo.getsockname()[1].to_bytes(2,"big")+payload
        udp.sendto(packet,("127.0.0.1",relay_port))
        answer,_=udp.recvfrom(2048)
        assert answer.endswith(payload),answer
        udp.close();control.close();echo.close()
        responder.join(timeout=3)
        print("N-LIC native authenticated TCP/UDP smoke OK")
    finally:
        task.send_signal(signal.SIGTERM)
        try:task.wait(timeout=2)
        except subprocess.TimeoutExpired:task.kill();task.wait()

if __name__=="__main__":main()
