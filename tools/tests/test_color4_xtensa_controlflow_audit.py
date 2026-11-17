"""Offline synthetic + private pinned OEM semantic regressions. No socket/network I/O."""
import unittest, tempfile, pathlib
from tools.color4_xtensa_controlflow_audit import FirmwareImage, decode, trace, stock_audit

class Fake:
    def __init__(self, code, pc): self.codebytes=bytes.fromhex(code);self.pc=pc
    def data(self, addr, count):
        idx=addr-self.pc
        return self.codebytes[idx:idx+count] if 0<=idx and idx+count<=len(self.codebytes) else None
    def u32(self,addr):return 0x12345678
    def code(self,addr):return self.pc<=addr<self.pc+len(self.codebytes)

class SyntheticInstructionTests(unittest.TestCase):
    # Xtensa LX public opcode examples, independent of proprietary firmware bytes.
    def check(self, code, pc, name, target=None, kind=None):
        ins=decode(Fake(bytes.fromhex(code)[::-1].hex(),pc),pc)
        self.assertIsNotNone(ins)
        self.assertEqual(ins.mnemonic,name)
        if target is not None:self.assertEqual(ins.target,target)
        if kind is not None:self.assertEqual(ins.kind,kind)
    def test_entry_ret(self):
        self.check('004136',0,'entry')
        self.check('f01d',0,'retw.n',kind='return')
        self.check('f00d',0,'ret.n',kind='return')
    def test_literal_math(self):
        self.check('fffc31',0x4008000f,'l32r',target=0x40080000)
    def test_branch_math(self):
        self.check('0aa926',0x40080997,'beqi',0x400809a5,'branch')
        self.check('f4a966',0x400809a2,'bnei',0x4008099a,'branch')
        self.check('f0bc',0x400804b0,'branch.n',0x400804f3,'branch')
        self.check('00f516',0x40080225-0xf-4,'beqz',0x40080225,'branch')
    def test_negative_backwards_branch(self):
        # Signed backwards 8-bit branch offset must not jump forwards.
        self.check('f4a966', 0x400809a2, 'bnei', 0x4008099a, 'branch')
    def test_narrow_l32i_valid_at_segment_end(self):
        self.check('0aa8', 0, 'OTHER')  # decoded length 2, not refused as short
    def test_narrow_branch_valid_at_segment_end(self):
        self.check('f0bc', 0x400804b0, 'branch.n', 0x400804f3, 'branch')
    def test_private_access_not_needed_for_synthetic(self):
        self.check('0008e0', 0, 'callx8', kind='indirect-call')
    def test_jumps_and_calls(self):
        self.check('011dd5',0x40080048,'call4',0x40081228,'call')
        self.check('044b46',0x4008004b,'j',0x4008117c,'jump')
        self.check('0008e0',0,'callx8',kind='indirect-call')
    def test_invalid_bundle(self):
        self.assertIsNone(decode(Fake('eeeeee',0x100),0x100))
    def test_fail_closed_foreign_image(self):
        with tempfile.NamedTemporaryFile() as x:
            x.write(b'\xe9'+b'\x00'*900); x.flush()
            with self.assertRaises(ValueError):FirmwareImage(x.name)
    def test_private_firmware_actual_controlflow(self):
        import os
        path = os.environ.get('COLOR4_STOCK_IMAGE')
        if not path:
            self.skipTest('Optional private original image (never included in public CI)')
        f=FirmwareImage(path)
        tcp=trace(f,0x40115d08,max_span=0x100)
        udp=trace(f,0x40122834,max_span=0x100)
        self.assertEqual(tcp.unknown,[])
        self.assertEqual(udp.unknown,[])
        self.assertIn(0x40115d0b,tcp.target_refs[0x3ffc01b8])
        self.assertIn(0x40122837,udp.target_refs[0x3ffc01b8])
        self.assertEqual(f.u32(0x40109b3c),0x3f40f90b)
        self.assertEqual(f.u32(0x4010a954),0x3f40f90b)
        self.assertEqual(tcp.calls[0x4013791c],[0x40115d1f])
        self.assertEqual(udp.calls[0x4013791c],[0x4012284b])
        dispatcher=trace(f,0x40122964,max_nodes=2000,max_span=0x1000)
        self.assertFalse(dispatcher.unknown)
        for key,location in [(0x3f40f8c4,0x40122ca4),(0x3f40f8d1,0x40122cbb),(0x3f40f8d1,0x40122e2f),(0x3f40f171,0x40122d85)]:
            self.assertIn(location,dispatcher.target_refs[key])
        self.assertIn(0x40122b9d,dispatcher.calls[0x40122834])
        udpstart=trace(f,0x40122ed4,max_nodes=1000,max_span=0x2000)
        self.assertFalse(udpstart.unknown)
        self.assertIn(0x40122fd4,udpstart.target_refs[0x40122964])
        self.assertEqual(f.u32(0x4010a9f8),0x40122964)
        self.assertIn(0x40122fee,udpstart.calls[0x4011f93c])
        lanstart=trace(f,0x40115e58,max_nodes=1000,max_span=0x2000)
        self.assertFalse(lanstart.unknown)
        self.assertIn(0x40115f75,lanstart.calls[0x40122ed4])
        report = stock_audit(path)
        self.assertEqual(report['structurally_explored_instruction_positions'], 501)
        self.assertFalse(report['quota_root_cause_proven'])
        self.assertFalse(report['chroma_runtime_enabled_proven'])
        self.assertTrue(report['udp_dispatcher_registered_in_startup_data'])

if __name__=='__main__': unittest.main(verbosity=2)
