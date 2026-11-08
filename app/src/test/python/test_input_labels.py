"""Regression checks for AOSP-style labels and Kotlin constants."""
import importlib.util
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[2] / 'main/cpp/generate-input.h-labels.py'
spec = importlib.util.spec_from_file_location('generator', SCRIPT)
generator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(generator)
FIXTURE = '''#define EV_SYN 0
#define EV_KEY 1
#define EV_REL 2
#define KEY_RESERVED 0
#define KEY_A 30
#define KEY_HANGEUL 122
#define KEY_HANGUEL KEY_HANGEUL
#define BTN_MOUSE 0x110
#define BTN_LEFT 0x110
#define REL_X 0
#define KEY_FUTURE 0x2aF /* Hexadecimal letters. */
#define KEY_MAX 0x2ff
#define KEY_CNT (KEY_MAX + 1)
#define INPUT_PROP_POINTER 0
'''


class InputLabelsTest(unittest.TestCase):
    def test_empty_input_fails(self):
        with self.assertRaises(ValueError):
            generator.parse('')

    def test_aliases_markers_and_namespaces(self):
        codes, labels = generator.parse(FIXTURE)
        self.assertEqual(codes['KEY_HANGUEL'], 122)
        self.assertEqual(codes['KEY_CNT'], 768)
        self.assertEqual(labels['key'][272], 'BTN_MOUSE')
        self.assertEqual(labels['key'][122], 'KEY_HANGEUL')
        self.assertEqual(labels['key'][687], 'KEY_FUTURE')
        self.assertEqual(labels['rel'][0], 'REL_X')
        self.assertNotIn(767, labels['key'])
        kotlin = generator.kotlin_codes(codes)
        self.assertIn('const val BTN_LEFT = 272', kotlin)
        self.assertIn('InputEventCodes.EV_KEY -> keyLabels[code]', generator.kotlin_labels(codes, labels))

    @unittest.skipUnless(shutil.which('c++'), 'host C++ compiler required')
    def test_generated_native_lookup(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'input.h'
            source.write_text(FIXTURE)
            header = root / 'generated/input.h-labels.h'
            subprocess.run(['python3', str(SCRIPT), str(source), '--native', str(header),
                            '--kotlin', str(root / 'kotlin')], check=True)
            self.assertTrue((root / 'kotlin/xtr/keymapper/InputEventCodes.kt').exists())
            self.assertTrue((root / 'kotlin/xtr/keymapper/InputEventLabels.kt').exists())
            (root / 'test.cpp').write_text('''
#include "generated/input.h-labels.h"
#include <cassert>
#include <cstring>
int main() {
    assert(std::strcmp(get_input_event_label(EV_KEY, 30), "KEY_A") == 0);
    assert(std::strcmp(get_input_event_label(EV_KEY, 122), "KEY_HANGEUL") == 0);
    assert(std::strcmp(get_input_event_label(EV_KEY, 272), "BTN_MOUSE") == 0);
    assert(std::strcmp(get_input_event_label(EV_REL, 0), "REL_X") == 0);
    assert(get_input_event_label(EV_KEY, -1) == nullptr);
    assert(get_input_event_label(EV_KEY, 767) == nullptr);
    assert(get_input_event_label(65535, 1) == nullptr);
}
''')
            subprocess.run(['c++', '-std=c++11', '-pedantic-errors', str(root / 'test.cpp'),
                            '-o', str(root / 'test')], check=True)
            subprocess.run([str(root / 'test')], check=True)


if __name__ == '__main__':
    unittest.main()
