import unittest
from prepare_navigation_lab import apk_filename

class ApkFilenameTest(unittest.TestCase):
    def test_official_repo_names(self):
        for package in ("net.osmand.plus","app.organicmaps","app.comaps.fdroid","org.navitproject.navit","no.navi.app"):
            name=package+"_123456.apk"
            self.assertEqual(apk_filename("/"+name),name)
            self.assertEqual(apk_filename(name),name)
    def test_path_and_url_attacks(self):
        for name in ("../file.apk","/../file.apk","//file.apk","nested/file.apk","https://other.example/a.apk","a.apk?x=1","a.apk#hash","a%2Fb.apk","a\\b.apk","..apk",".hidden.apk","a b.apk","a.apk\n","a.zip","/","",None):
            with self.subTest(name=name),self.assertRaises(ValueError):
                apk_filename(name)
    def test_maximum_filename_length(self):
        with self.assertRaises(ValueError):
            apk_filename("a"*252+".apk")
if __name__=="__main__":
    unittest.main()
