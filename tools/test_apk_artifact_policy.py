import datetime as dt, unittest
from apk_artifact_policy import decision
class ExpiryTest(unittest.TestCase):
 def setUp(self):self.now=dt.datetime(2026,11,26,12,tzinfo=dt.timezone.utc)
 def a(self,name,age,branch="main"):
  return {"id":1,"name":name,"created_at":(self.now-dt.timedelta(seconds=age)).isoformat(),"workflow_run":{"head_branch":branch},"expired":False}
 def test_boundary(self):
  name="DriveMate-2.1.0-signed-apk-1h"
  self.assertIsNone(decision(self.a(name,3599),self.now))
  self.assertEqual("one-hour APK expiry",decision(self.a(name,3600),self.now))
  self.assertIsNotNone(decision(self.a(name,7200),self.now))
 def test_unsafe(self):
  for name in ["DriveMate-debug-apk","unsigned-apk","build.aab","DriveMate-2.1-unsigned"]:
   self.assertIsNotNone(decision(self.a(name,1),self.now))
  self.assertIsNotNone(decision(self.a("DriveMate-2.1.0-signed-apk-1h",1,"feature"),self.now))
 def test_legacy_and_evidence(self):
  self.assertIsNotNone(decision(self.a("DriveMate-2.0.2-password-protected",3600),self.now))
  for name in ["DriveMate-2.1.0-release-evidence","Android-apksigner-tool","navigation-soak-evidence"]:
   self.assertIsNone(decision(self.a(name,7200),self.now))
 def test_invalid_timestamp(self):
  a=self.a("DriveMate-2.1.0-signed-apk-1h",0);a["created_at"]="bad"
  self.assertIsNotNone(decision(a,self.now))
