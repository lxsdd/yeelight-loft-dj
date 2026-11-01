"""Synthetic-only CALL8 candidate tests: never include stock firmware bytes."""
import json
import unittest
from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import color4_quota_callgraph_candidates as m
from color4_quota_literal_xrefs import Segment


def call8(pc, dest):
    delta = dest - ((pc & ~3) + 4)
    assert delta % 4 == 0 and -(1 << 17) <= delta // 4 < (1 << 17)
    return ((delta // 4 & 0x3ffff) << 6 | 0x25).to_bytes(3, 'little')


def build_synthetic(*, break_first=False, break_second=False, pointer_dense=False,
                    same_helper=True, include_data_noise=False):
    base = 0x400D0000
    img = bytearray(8192)
    seg = Segment(0, base, len(img), 'possible_instruction_segment')
    helper = base + 6000
    first, second = base + 200, base + 500
    for pos, target, broken in ((200, helper, break_first),
                                (500, helper if same_helper else base+6400, break_second)):
        img[pos:pos+3] = bytes((0x36, 0x41, 0x01))
        img[pos+3:pos+6] = bytes((0x81, 0xf0, 0xff))
        if not broken:
            img[pos+23:pos+26] = call8(base+pos+23, target)
    img[6000:6003] = bytes((0x36, 0x81, 0x01))
    img[6400:6403] = bytes((0x36, 0x41, 0x01))
    img[800:803] = call8(base+800, first)
    img[900:903] = call8(base+900, second)
    segments = [seg]
    if include_data_noise:
        img[2000:2003] = call8(base+2000, first)
        segments = [Segment(0, base, 1500, 'possible_instruction_segment'),
                    Segment(1500, base+1500, 1500, 'data_segment'),
                    Segment(3000, base+3000, 5192, 'possible_instruction_segment')]
    evidence = {'error_table': {'candidate_pc_relative_l32r': [
        {'instruction_address_hex': hex(base+pos+3), 'context': {
            'preceded_by_common_entry_bytes_hint': True,
            'pointer_dense_data_warning': pointer_dense and pos == 200}}
        for pos in (200, 500)]}}
    return bytes(img), segments, evidence


class Call8CandidateGraphTests(unittest.TestCase):
    def test_isa_forward_and_backward_word_displacements(self):
        for pc, target in ((0x40100001, 0x400ffffc),
                           (0x40100021, 0x4011fffc),
                           (0x40130001, 0x40110008)):
            self.assertEqual(m.call8_target(call8(pc, target), pc), target)

    def test_negative_one_word_and_18bit_boundary(self):
        pc = 0x40120000
        for words in (-(1 << 17), (1 << 17)-1, -1, 0):
            target = ((pc & ~3) + 4*(words+1)) & 0xffffffff
            self.assertEqual(m.call8_target(call8(pc, target), pc), target)

    def test_invalid_opcode_length_and_pc_rejected(self):
        for packet, pc in ((b'\x00\x00\x00', 0x40100000),
                           (b'\x25\xff', 0x40100000),
                           (b'\x25\x00\x00', -1),
                           (b'\x25\x00\x00', 0x100000000),
                           (b'\x15\x00\x00', 0x40100000)):
            self.assertIsNone(m.call8_target(packet, pc))

    def test_two_wrapper_candidates_share_helper_and_one_inbound_each(self):
        image, segments, evidence = build_synthetic()
        r = m.candidate_graph(image, segments, evidence)
        self.assertEqual(r['candidate_entry_count'], 2)
        self.assertEqual(r['shared_helper_candidate_hex'], hex(0x400d0000+6000))
        self.assertEqual([c['raw_inbound_call8_count']
                          for c in r['candidate_entry_details']], [1, 1])
        self.assertTrue(all(c['callee_has_common_entry_prefix']
                            for c in r['candidate_entry_details']))
        self.assertFalse(r['full_xtensa_disassembly_performed'])
        self.assertFalse(r['quota_counter_or_refill_function_identified'])
        self.assertFalse(r['firmware_patch_safe'])

    def test_missing_call8_rejects_candidate_and_common_helper(self):
        image, segments, evidence = build_synthetic(break_first=True)
        r = m.candidate_graph(image, segments, evidence)
        self.assertEqual(r['candidate_entry_count'], 1)
        self.assertIsNone(r['shared_helper_candidate_hex'])

    def test_no_matching_helper_is_not_claimed(self):
        image, segments, evidence = build_synthetic(same_helper=False)
        r = m.candidate_graph(image, segments, evidence)
        self.assertEqual(r['candidate_entry_count'], 2)
        self.assertIsNone(r['shared_helper_candidate_hex'])

    def test_pointer_dense_candidate_is_not_promoted(self):
        image, segments, evidence = build_synthetic(pointer_dense=True)
        r = m.candidate_graph(image, segments, evidence)
        self.assertEqual(r['candidate_entry_count'], 1)
        self.assertIsNone(r['shared_helper_candidate_hex'])

    def test_missing_entry_anchor_rejects_candidate(self):
        image, segments, evidence = build_synthetic()
        raw = bytearray(image)
        raw[200] = 0
        self.assertEqual(m.candidate_graph(bytes(raw), segments, evidence)[
            'candidate_entry_count'], 1)

    def test_reverse_call_excludes_nonexecutable_section(self):
        image, segments, evidence = build_synthetic(include_data_noise=True)
        r = m.candidate_graph(image, segments, evidence)
        self.assertEqual(r['candidate_entry_count'], 2)
        self.assertEqual(r['candidate_entry_details'][0]['raw_inbound_call8_count'], 1)

    def test_changed_helper_prologue_is_not_proof_of_real_function(self):
        image, segments, evidence = build_synthetic()
        raw = bytearray(image)
        raw[6000] = 0
        r = m.candidate_graph(bytes(raw), segments, evidence)
        self.assertFalse(all(c['callee_has_common_entry_prefix']
                             for c in r['candidate_entry_details']))
        self.assertFalse(any(c['function_reachable_proven']
                             for c in r['candidate_entry_details']))

    def test_bounded_report_redacts_raw_bytes_and_secrets(self):
        image, segments, evidence = build_synthetic()
        r = m.candidate_graph(image, segments, evidence)
        text = json.dumps(r)
        self.assertLess(len(text), 8000)
        self.assertNotIn('token', text)
        self.assertNotIn('firmware_bytes', text)
        self.assertNotIn('192.168.', text)
        self.assertTrue(all(not c['inbound_instruction_boundaries_proven']
                            for c in r['candidate_entry_details']))

    def test_exact_stock_required_even_with_synthetic_candidate(self):
        image, _, _ = build_synthetic()
        r = m.analyze_private_stock(image)
        self.assertEqual(r['status'], 'REJECTED')
        self.assertFalse(r['stock_image_verified'])
        self.assertNotIn('graph', r)


if __name__ == '__main__':
    unittest.main()
