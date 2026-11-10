"""Guard complete UI resource coverage and Android format argument compatibility."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2] / 'app/src'
FORMAT = re.compile(r'%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z]')


def resources(directory):
    result = {}
    for path in directory.glob('*.xml'):
        for node in ET.parse(path).getroot():
            if node.tag not in ('string', 'plurals', 'string-array'):
                continue
            key = (node.tag, node.get('name'))
            if key in result:
                raise AssertionError(f'Duplicate resource: {directory}/{key}')
            result[key] = node
    return result


class ChineseLocalizationTest(unittest.TestCase):
    def test_all_translatable_strings_have_chinese_and_preserve_arguments(self):
        for variant in ('main', 'debug', 'release'):
            english = resources(ROOT / variant / 'res/values')
            chinese = resources(ROOT / variant / 'res/values-zh')
            for key, node in english.items():
                if node.get('translatable') == 'false':
                    continue
                with self.subTest(variant=variant, name=key):
                    self.assertTrue(key in chinese, f"Missing Chinese resource: {key}")
                    translated = chinese[key]
                    if node.tag == 'plurals':
                        original = node.find("item[@quantity='other']")
                        target = translated.find("item[@quantity='other']")
                        self.assertIsNotNone(target)
                        self.assertEqual(sorted(FORMAT.findall(original.text or '')),
                                         sorted(FORMAT.findall(target.text or '')))
                    elif node.tag == 'string':
                        self.assertEqual(sorted(FORMAT.findall(node.text or '')),
                                         sorted(FORMAT.findall(translated.text or '')))
                        self.assertTrue(translated.text)

    def test_declared_languages_match_picker(self):
        config = ET.parse(ROOT / 'main/res/xml/locales_config.xml').getroot()
        languages = {node.get('{http://schemas.android.com/apk/res/android}name') for node in config}
        self.assertEqual({'en', 'zh'}, languages)
