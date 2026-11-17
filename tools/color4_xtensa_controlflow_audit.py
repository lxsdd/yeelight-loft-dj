"""Read-only internal targeted Xtensa core flow audit for one SHA256-pinned OEM image.
Not a full disassembler: in particular, indirect jumps, interrupts, table calls,
branch delay oddities or LITBASE are not proven. Never exports binary bytes.
"""
from collections import defaultdict, deque
from dataclasses import dataclass
from pathlib import Path
import argparse
import json
from struct import unpack_from
import hashlib

EXPECTED='67f075ca909ae9f5c08ff580a4847683d1899e8ba251a7cd75d275b9848f22aa'

class FirmwareImage:
 def __init__(self, path):
  self.bytes=Path(path).read_bytes()
  if len(self.bytes)!=1502532 or hashlib.sha256(self.bytes).hexdigest()!=EXPECTED:
   raise ValueError('wrong image identity')
  self.segments=[]
  if self.bytes[0]!=0xe9 or self.bytes[1]!=7:raise ValueError('wrong ESP32 segment header')
  pos=24
  for _ in range(7):
   v,n=unpack_from('<II',self.bytes,pos);pos+=8
   if n==0 or pos+n>len(self.bytes):raise ValueError('bad segment')
   self.segments.append((v,v+n,pos,pos+n))
   pos+=n
 def file_offset(self,addr,size=1):
  for low,high,base,end in self.segments:
   if low<=addr and addr+size<=high:return base+addr-low
  return None
 def data(self,addr,size):
  offset=self.file_offset(addr,size)
  return self.bytes[offset:offset+size] if offset is not None else None
 def u32(self,addr):
  d=self.data(addr,4)
  return int.from_bytes(d,'little') if d is not None else None
 def code(self,addr):
  return any(low<=addr<hi and (low>=0x40080000 and low<0x40400000) for low,hi,base,end in self.segments)

def sext(v,n):return v-(1<<n) if v&(1<<(n-1)) else v
@dataclass(frozen=True)
class Instruction:
 pc:int
 size:int
 mnemonic:str
 target:int|None=None
 literal:int|None=None
 kind:str='fallthrough'


def decode(f,pc):
 head=f.data(pc,2)
 if head is None:return None
 op=head[0]&15
 if op>=14:return None
 narrow=8<=op<=13
 size=2 if narrow else 3
 d=head if narrow else f.data(pc,3)
 if d is None:return None
 val=int.from_bytes(d,'little')
 t,s,r=(val>>4)&15,(val>>8)&15,(val>>12)&15
 op1=(val>>16)&15
 op2=(val>>20)&15
 advance=pc+size
 name='OTHER';target=None;literal=None;kind='fallthrough'
 if op==0:
  if op1==0 and op2==0 and r==0 and s==0 and t in (8,9):
   name='ret' if t==8 else 'retw'; kind='return'
  elif op1==0 and op2==0 and r==0 and t in (10,12,13,14,15):
   name='jx' if t==10 else f'callx{(t-12)*4}'
   kind='indirect-jump' if t==10 else 'indirect-call'
 elif op==1:
  name='l32r';offset=((val>>8)&0xffff)-65536
  target=((pc+3)&~3)+offset*4
  literal=f.u32(target)
 elif op==2:
  name={0:'l8ui',1:'l16ui',2:'l32i',4:'s8i',5:'s16i',6:'s32i',9:'l16si',10:'movi',12:'addi',13:'addmi'}.get(r,'other-load-store')
 elif op==5:
  name=['call0','call4','call8','call12'][t&3];kind='call'
  target=(pc&~3)+4+4*sext((val>>6)&0x3ffff,18)
 elif op==6:
  if (t&3)==0:
   name='j';kind='jump';target=pc+4+sext((val>>6)&0x3ffff,18)
  elif t==3:
   name='entry'
  elif t in (1,5,9,13):
   name={1:'beqz',5:'bnez',9:'bltz',13:'bgez'}[t];kind='branch'
   target=pc+4+sext((val>>12)&0xfff,12)
  elif t in (2,6,10,14,11,15):
   name={2:'beqi',6:'bnei',10:'blti',14:'bgei',11:'bltui',15:'bgeui'}[t];kind='branch'
   target=pc+4+sext((val>>16)&0xff,8)
  elif t==7:
   name={0:'bf',1:'bt',8:'loop',9:'loopnez',10:'loopgtz'}.get(r,'other-branch')
   if r in (0,1):
    kind='branch';target=pc+4+sext((val>>16)&0xff,8)
 elif op==7:
  name='b-reg';kind='branch'
  target=pc+4+sext((val>>16)&0xff,8)
 elif op==0xc:
  if t&8:
   name='branch.n';kind='branch';target=pc+4+(r|((t&3)<<4))
  else:name='movi.n'
 elif op==0xd and r==15:
  if t==0 and s==0:name='ret.n';kind='return'
  elif t==1 and s==0:name='retw.n';kind='return'
 return Instruction(pc,size,name,target,literal,kind)

@dataclass
class Trace:
 visited:set[int]
 target_refs:dict[int,list[int]]
 calls:dict[int,list[int]]
 unknown:list[int]


def trace(f,entry,max_nodes=10000,max_span=0x4000):
 if not f.code(entry):raise ValueError('not code')
 queue=deque([entry]);visited=set();xrefs=defaultdict(list);calls=defaultdict(list);unknown=[]
 while queue and len(visited)<max_nodes:
  pc=queue.popleft()
  if pc in visited or abs(pc-entry)>max_span or not f.code(pc):continue
  visited.add(pc)
  i=decode(f,pc)
  if i is None:
   unknown.append(pc);continue
  if i.mnemonic=='l32r' and i.literal is not None:xrefs[i.literal].append(pc)
  if i.kind=='call' and i.target is not None:calls[i.target].append(pc)
  if i.kind=='return' or i.kind=='indirect-jump':continue
  if i.kind=='jump':
   if i.target is not None:queue.append(i.target)
   continue
  if i.kind=='branch' and i.target is not None:queue.append(i.target)
  queue.append(pc+i.size)
 return Trace(visited,xrefs,calls,unknown)

def stock_audit(image_path):
    """Public-safe address metadata, never OEM instructions or payloads."""
    f = FirmwareImage(image_path)
    tcp = trace(f, 0x40115d08, max_span=0x100)
    udp_err = trace(f, 0x40122834, max_span=0x100)
    udp_handler = trace(f, 0x40122964, max_nodes=2000, max_span=0x1000)
    udp_start = trace(f, 0x40122ed4, max_nodes=1000, max_span=0x2000)
    lan_start = trace(f, 0x40115e58, max_nodes=1000, max_span=0x2000)
    if any(t.unknown for t in (tcp, udp_err, udp_handler, udp_start, lan_start)):
        raise ValueError('unresolved branch or invalid decoded instruction')
    expected = (
        0x40115d0b in tcp.target_refs[0x3ffc01b8]
        and 0x40122837 in udp_err.target_refs[0x3ffc01b8]
        and f.u32(0x40109b3c) == 0x3f40f90b
        and f.u32(0x4010a954) == 0x3f40f90b
        and 0x40122ca4 in udp_handler.target_refs[0x3f40f8c4]
        and 0x40122cbb in udp_handler.target_refs[0x3f40f8d1]
        and 0x40122e2f in udp_handler.target_refs[0x3f40f8d1]
        and 0x40122d85 in udp_handler.target_refs[0x3f40f171]
        and 0x40122b9d in udp_handler.calls[0x40122834]
        and 0x40122fd4 in udp_start.target_refs[0x40122964]
        and f.u32(0x4010a9f8) == 0x40122964
        and 0x40115f75 in lan_start.calls[0x40122ed4]
    )
    # Exact fixed-code semantics: 32-bit error-table index, both wrappers.
    for shift, index, load in ((0x40115d0e, 0x40115d11, 0x40115d13),
                               (0x4012283a, 0x4012283d, 0x4012283f)):
        if f.data(shift, 1) is None:
            expected = False
        shift_data = f.data(shift, 3)
        if shift_data is None or (int.from_bytes(shift_data, 'little') & 15) != 0:
            expected = False
        # The source and destination register are both a3, shift amount 2.
        if shift_data is None or ((int.from_bytes(shift_data, 'little') >> 20) & 15) != 1:
            expected = False
        if decode(f, index) is None or decode(f, load) is None:
            expected = False
    # The selected function pointer is written unchanged into the UDP context:
    # L32R a3, [0x4010A9F8]; L32I a10,a2; S32I a3,a2,+132.
    ptr_load = f.data(0x40122fd4, 3)
    ptr_store = f.data(0x40122fda, 3)
    if ptr_load is None or ptr_store is None:
        expected = False
    else:
        load_reg = (int.from_bytes(ptr_load, 'little') >> 4) & 15
        write = int.from_bytes(ptr_store, 'little')
        expected = expected and load_reg == 3 and (write & 15) == 2 and (
            (write >> 4) & 15, (write >> 8) & 15, (write >> 12) & 15, (write >> 16) & 255
        ) == (3, 2, 6, 33)
    if not expected:
        raise ValueError('OEM reference chain disagrees with pinned evidence')
    return {
        'original_sha256': EXPECTED,
        'analysis_kind': 'partial_static_xtensa_cfg_not_emulation',
        'quota_error_formatters': ['0x40115D08','0x40122834'],
        'quota_status_table': '0x3FFC01B8',
        'quota_root_cause_proven': False,
        'chroma_dispatch_entry': '0x40122964',
        'chroma_method_reference_sites': ['0x40122CBB','0x40122E2F'],
        'udp_registers_dispatcher_pointer_at': '0x40122FD4',
        'structurally_explored_instruction_positions': len(udp_handler.visited),
        'decoder_rejected_positions': len(udp_handler.unknown),
        'full_isa_validation_or_runtime_branch_feasibility_proven': False,
        'chroma_compiled_code_path_supported': True,
        'udp_dispatcher_registered_in_startup_data': True,
        'lan_initialization_call_to_udp_start': '0x40115F75',
        'chroma_runtime_enabled_proven': False,
        'hardware_tested': False,
        'firmware_modified': False,
    }


def main():
    parser = argparse.ArgumentParser(description='Offline read-only original Color4 Xtensa control-flow audit')
    parser.add_argument('stock_image', help='Private exact original image, not to be uploaded')
    args = parser.parse_args()
    try:
        print(json.dumps(stock_audit(args.stock_image), indent=2, sort_keys=True))
    except (OSError, ValueError) as err:
        parser.exit(2, 'REJECTED: original identity or evidence check failed\n')


if __name__ == '__main__':
    main()
