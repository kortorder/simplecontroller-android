"""Receiver unit/loopback tests. Native outputs are replaced by recording fakes."""
import sys
import unittest

def flatten(tests):
    for test in tests:
        if isinstance(test, unittest.TestSuite):
            yield from flatten(test)
        else:
            yield test

def main():
    selected=[]; excluded=[]
    for test in flatten(unittest.defaultTestLoader.discover('.')):
        if '.TaggedAbiTests.' in test.id() and sys.platform != 'win32':
            excluded.append(test.id())
        else:
            selected.append(test)
    if excluded:
        print('Windows-only native ABI tests excluded on this host:', *excluded, sep='\n', flush=True)
    result=unittest.TextTestRunner(verbosity=1).run(unittest.TestSuite(selected))
    return 0 if result.wasSuccessful() else 1

if __name__=='__main__':
    raise SystemExit(main())
