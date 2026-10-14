"""Fast TCP port probe. Test-NetConnection takes seconds per closed port; this
takes milliseconds. Usage: python fastscan.py 192.168.1.0/24 5555 80 8080 23456 10000"""
import socket, sys, concurrent.futures, ipaddress

def probe(args):
    ip, port = args
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(0.35)
    try:
        if s.connect_ex((ip, port)) == 0:
            return (ip, port)
    except Exception:
        pass
    finally:
        s.close()
    return None

def main():
    cidr = sys.argv[1]
    ports = [int(p) for p in sys.argv[2:]]
    hosts = [str(h) for h in ipaddress.ip_network(cidr, strict=False).hosts()]
    tasks = [(ip, p) for ip in hosts for p in ports]
    print(f"scanning {len(hosts)} hosts x {len(ports)} ports = {len(tasks)} probes ...", flush=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=256) as ex:
        hits = [r for r in ex.map(probe, tasks) if r]
    if not hits:
        print("no open ports found")
    else:
        for ip, port in sorted(hits, key=lambda x: (int(ipaddress.ip_address(x[0])), x[1])):
            print(f"  {ip}:{port}")

if __name__ == "__main__":
    main()