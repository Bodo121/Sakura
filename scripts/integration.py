"""Real daemon/CLI smoke test, malformed input and measured local IPC timings."""
import json,os,subprocess,tempfile,time,socket,struct,statistics
from pathlib import Path
bin=Path('target/debug')
with tempfile.TemporaryDirectory() as d:
 os.chmod(d,0o700)
 env=dict(os.environ,SAKURA_STATE_DIR=d)
 start=time.perf_counter_ns()
 daemon=subprocess.Popen([str(bin/'sakurad'),'--state-dir',d],stderr=subprocess.PIPE)
 try:
  for _ in range(200):
   if Path(d,'core.sock').exists():break
   if daemon.poll() is not None:raise RuntimeError(daemon.stderr.read().decode())
   time.sleep(.005)
  startup_ms=(time.perf_counter_ns()-start)/1e6
  assert (Path(d,'core.sock').stat().st_mode&0o777)==0o600
  duplicate=subprocess.run([str(bin/'sakurad'),'--state-dir',d],capture_output=True)
  assert duplicate.returncode!=0
  samples=[]
  for _ in range(30):
   t=time.perf_counter_ns();r=subprocess.run([str(bin/'sakura'),'status'],env=env,capture_output=True,check=True);samples.append((time.perf_counter_ns()-t)/1e6)
   assert json.loads(r.stdout)['protocol']==1
  for cmd in ['hardware','services','diagnostics']:
   json.loads(subprocess.check_output([str(bin/'sakura'),cmd],env=env))
  s=socket.socket(socket.AF_UNIX);s.connect(d+'/core.sock');s.sendall(struct.pack('>I',2**32-1));s.close()
  subprocess.run([str(bin/'sakura'),'status'],env=env,check=True,capture_output=True)
  # Idle CPU: /proc ticks, measured on this build host only.
  before=int(Path(f'/proc/{daemon.pid}/stat').read_text().split()[13])+int(Path(f'/proc/{daemon.pid}/stat').read_text().split()[14])
  time.sleep(1)
  after=int(Path(f'/proc/{daemon.pid}/stat').read_text().split()[13])+int(Path(f'/proc/{daemon.pid}/stat').read_text().split()[14])
  rss=next(x for x in Path(f'/proc/{daemon.pid}/status').read_text().splitlines() if x.startswith('VmRSS:'))
  subprocess.run([str(bin/'sakura'),'service','stop'],env=env,check=True,capture_output=True)
  daemon.wait(timeout=5);assert daemon.returncode==0
  assert not Path(d,'core.sock').exists()
  result={'host_arch':os.uname().machine,'debug_build':True,'daemon_startup_observed_ms':startup_ms,'cli_status_process_plus_ipc_p50_ms':statistics.median(samples),'cli_status_process_plus_ipc_max_ms':max(samples),'idle_cpu_ticks_over_1s':after-before,'clock_ticks_per_second':os.sysconf('SC_CLK_TCK'),'rss':rss,'samples':len(samples),'physical_device_tested':False}
  Path('dist').mkdir(exist_ok=True);Path('dist/host-benchmarks.json').write_text(json.dumps(result,indent=2));print(json.dumps(result,indent=2))
 finally:
  if daemon.poll() is None:daemon.kill();daemon.wait()
