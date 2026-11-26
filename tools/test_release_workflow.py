import unittest
from bump_release_version import advance

class VersionTest(unittest.TestCase):
    def setUp(self):
        self.v={"versionName":"2.0.2","versionCode":200014,"lastLegacyDistributedCode":200013}
        self.g="    versionCode 200014\n    versionName '2.0.2'\n"
    def test_advance(self):
        v,g=advance(self.v,self.g,"2.0.3")
        self.assertEqual(v["versionCode"],200015)
        self.assertIn("versionName '2.0.3'",g)
        self.assertEqual(self.v["versionCode"],200014)
    def test_reject_duplicate_or_old(self):
        for name in ("2.0.2","1.9.9","2.0.3-rc"):
            with self.assertRaises(ValueError):advance(self.v,self.g,name)
    def test_reject_drift(self):
        with self.assertRaises(ValueError):advance(self.v,self.g.replace("200014","200015"),"2.0.3")
