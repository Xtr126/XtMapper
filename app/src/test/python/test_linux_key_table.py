"""Regression checks for canonical labels and the generated C++ lookup."""
import importlib.util
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[2] / 'main/cpp/generate_linux_key_table.py'
spec = importlib.util.spec_from_file_location('generator', SCRIPT)
generator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(generator)

FIXTURE = '''#define KEY_RESERVED 0
#define KEY_ESC 1
#define KEY_A 30
#define KEY_HANGEUL 122
#define KEY_HANGUEL KEY_HANGEUL
#define KEY_DUPLICATE 122
#define BTN_LEFT 0x110
#define KEY_FUTURE 0x2aF /* Includes hexadecimal letters. */
#define KEY_MAX 0x2ff
#define KEY_CNT (KEY_MAX + 1)
#define KEY_MIN_INTERESTING KEY_ESC
'''


class LinuxKeyTableTest(unittest.TestCase):
    def test_empty_input_fails(self):
        with self.assertRaises(ValueError):
            generator.generate('')

    def test_canonical_names_and_exclusions(self):
        header = generator.generate(FIXTURE)
        self.assertIn('case 687: return "KEY_FUTURE";', header)
        for excluded in ['KEY_RESERVED', 'KEY_HANGUEL', 'KEY_DUPLICATE',
                         'BTN_LEFT', 'KEY_MAX', 'KEY_CNT', 'KEY_MIN_INTERESTING']:
            self.assertNotIn(excluded, header)

    @unittest.skipUnless(shutil.which('c++'), 'host C++ compiler required')
    def test_generated_lookup(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'input-event-codes.h'
            source.write_text(FIXTURE)
            header = root / 'generated/linux_key_table.h'
            subprocess.run(['python3', str(SCRIPT), str(source), str(header)], check=True)
            (root / 'test.cpp').write_text('''
#include "generated/linux_key_table.h"
#include <cassert>
#include <cstring>
int main() {
    assert(std::strcmp(get_linux_key_name(30), "KEY_A") == 0);
    assert(std::strcmp(get_linux_key_name(122), "KEY_HANGEUL") == 0);
    assert(std::strcmp(get_linux_key_name(687), "KEY_FUTURE") == 0);
    for (int code : {-1, 0, 2, 272, 767, 768, 65535})
        assert(get_linux_key_name(code) == nullptr);
}
'''.replace('#include <cassert>', '#include <cassert>\n#include <initializer_list>'))
            subprocess.run(['c++', '-std=c++11', '-pedantic-errors', str(root / 'test.cpp'),
                            '-o', str(root / 'test')], check=True)
            subprocess.run([str(root / 'test')], check=True)


if __name__ == '__main__':
    unittest.main()
