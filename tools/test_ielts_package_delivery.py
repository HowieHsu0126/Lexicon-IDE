"""Offline publication checks for native versus historic output authority."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

import harness_ielts as native
from test_ielts_pipeline import runtime_receipts, make_native_receipt
from test_ielts_review import review_fixture

ROOT=Path(__file__).resolve().parents[1]
SPEC=importlib.util.spec_from_file_location('lexicon_native_package_checks',
    ROOT/'dist/validation-ielts-pairing-2026-11-09/package-136.py')
package=importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(package)


class PackageDeliveryTest(unittest.TestCase):
    def setUp(self):
        (ROOT/'.build-support').mkdir(exist_ok=True)
        self.scratch=TemporaryDirectory(dir=ROOT/'.build-support',prefix='native-package-')
        self.addCleanup(self.scratch.cleanup)
        self.stage=Path(self.scratch.name)
        (self.stage/'state').mkdir()
        request,candidate,review=review_fixture()
        self.receipt=runtime_receipts(request,candidate,review,review['review_id'])[0]
        self.raw=json.dumps(candidate,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()
        self.receipt['candidate_bytes']=len(self.raw)
        self.receipt['tool_receipt'].update(candidateBytes=len(self.raw),candidateSha256=hashlib.sha256(self.raw).hexdigest())
        self.native=make_native_receipt(copy.deepcopy(self.receipt),candidate)
        self.path=self.stage/'state/validated-candidate.json'
        self.path.write_bytes(self.raw)

    def check(self,receipt,manifest):
        inputs=package.Inputs()
        delivery=package.runtime_delivery(inputs,self.stage,receipt,manifest)
        inputs.unchanged()
        return delivery

    def test_absent_historic_discriminator_retains_legacy_confirmation(self):
        self.assertEqual(native.LEGACY_DELIVERY,self.check(self.receipt,{}))

    def test_native_discriminator_and_exact_bytes_accept(self):
        self.assertEqual(native.NATIVE_DELIVERY,self.check(self.native,{'candidateDelivery':native.NATIVE_DELIVERY}))

    def test_manifest_and_receipt_must_agree_in_both_directions(self):
        for receipt,manifest in ((self.native,{}),(self.native,{'candidateDelivery':native.LEGACY_DELIVERY}),
                                 (self.receipt,{'candidateDelivery':native.NATIVE_DELIVERY})):
            with self.assertRaises(package.PackageError):self.check(receipt,manifest)

    def test_original_candidate_bytes_cannot_be_replaced_by_equivalent_json(self):
        self.path.write_bytes(self.raw+b'\n')
        for receipt in (self.native,self.receipt):
            with self.assertRaisesRegex(package.PackageError,'runtime_candidate_bytes_mismatch'):
                self.check(receipt,{'candidateDelivery':native.candidate_delivery_kind(receipt)})

    def test_missing_native_commit_and_forged_legacy_confirmation_fail(self):
        for mutation in ('missing','mixed','session'):
            receipt=copy.deepcopy(self.native)
            if mutation=='missing':receipt['tool_receipt'].pop('nativeToolConclusion')
            if mutation=='mixed':receipt['candidate_confirmation_verified']=True
            if mutation=='session':receipt['tool_receipt']['nativeToolConclusion']['sessionId']='session-other'
            with self.assertRaisesRegex(package.PackageError,'runtime_candidate_delivery_invalid'):
                self.check(receipt,{'candidateDelivery':native.NATIVE_DELIVERY})


if __name__=='__main__':unittest.main()
