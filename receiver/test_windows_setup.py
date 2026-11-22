import contextlib
import io
import subprocess
import unittest
from pathlib import Path
from unittest.mock import patch
import setup_receiver
import start_receiver

class WindowsSetupTests(unittest.TestCase):
    def test_unsupported_os_architecture_and_python_fail_before_installation(self):
        for system,machine,bits,version in [('darwin','arm64',8,(3,13)),('win32','ARM64',8,(3,13)),('win32','AMD64',4,(3,13)),('win32','AMD64',8,(3,9))]:
            with self.subTest(system=system,machine=machine,bits=bits,version=version), patch.object(setup_receiver.sys,'platform',system),patch.object(setup_receiver.platform,'machine',return_value=machine),patch.object(setup_receiver.struct,'calcsize',return_value=bits),patch.object(setup_receiver.sys,'version_info',version):
                self.assertIsNotNone(setup_receiver.platform_problem())
    def test_venv_python_installs_pinned_requirements_and_skips_bundled_driver(self):
        with patch.object(setup_receiver,'platform_problem',return_value=None),patch.object(setup_receiver.venv,'EnvBuilder') as builder,patch.object(setup_receiver.subprocess,'run') as run,contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(setup_receiver.main(),0)
            self.assertTrue(builder.call_args.kwargs['with_pip'])
            argv=run.call_args.args[0]
            self.assertEqual(Path(argv[0]).parts[-3:], ('.venv', 'Scripts', 'python.exe'))
            self.assertEqual(argv[1:4],['-m','pip','install'])
            self.assertTrue(argv[-1].endswith('requirements-windows.txt'))
            self.assertEqual(run.call_args.kwargs['env']['VGAMEPAD_SKIP_VIGEMBUS_INSTALL'],'true')
            self.assertTrue(run.call_args.kwargs['check'])
    def test_venv_and_package_failures_stop_setup_with_visible_error(self):
        for stage in ('venv','pip'):
            with self.subTest(stage=stage),patch.object(setup_receiver,'platform_problem',return_value=None),patch.object(setup_receiver.venv,'EnvBuilder') as builder,patch.object(setup_receiver.subprocess,'run') as run,contextlib.redirect_stdout(io.StringIO()) as output:
                if stage=='venv':builder.return_value.create.side_effect=OSError('read-only folder')
                else:run.side_effect=subprocess.CalledProcessError(1,['pip'])
                self.assertEqual(setup_receiver.main(),1)
                self.assertIn('Setup failed',output.getvalue())
                if stage=='venv':run.assert_not_called()
    def test_launcher_reports_missing_packages_and_native_driver_failure(self):
        with patch.object(start_receiver,'platform_problem',return_value=None),patch.object(start_receiver.importlib.util,'find_spec',return_value=None),patch.object(start_receiver.runpy,'run_path') as run,contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(start_receiver.main(),1);self.assertIn('Setup-Windows-Receiver.bat',output.getvalue());run.assert_not_called()
        with patch.object(start_receiver,'platform_problem',return_value=None),patch.object(start_receiver.importlib.util,'find_spec',return_value=object()),patch.object(start_receiver.runpy,'run_path',side_effect=RuntimeError('ViGEmBus missing')),contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(start_receiver.main(),1);self.assertIn(setup_receiver.DRIVER_URL,output.getvalue())
    def test_launcher_executes_only_local_receiver_without_install_or_elevation(self):
        with patch.object(start_receiver,'platform_problem',return_value=None),patch.object(start_receiver.importlib.util,'find_spec',return_value=object()),patch.object(start_receiver.runpy,'run_path') as run,contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(start_receiver.main(),0)
            self.assertTrue(run.call_args.args[0].endswith('simple_controller_receiver.py'))
            self.assertEqual(run.call_args.kwargs,{'run_name':'__main__'})

if __name__=='__main__':unittest.main()
