import { existsSync, mkdirSync, mkdtempSync, readdirSync, rmSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.dirname(fileURLToPath(import.meta.url));
const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
if (!sdk) throw new Error('請將 ANDROID_HOME 設為 Android SDK 資料夾。');
const platform = path.join(sdk, 'platforms', 'android-35', 'android.jar');
const tools = path.join(sdk, 'build-tools', '35.0.0');
const suffix = process.platform === 'win32' ? '.exe' : '';
const output = path.join(root, 'dist');
mkdirSync(output, { recursive: true });

function jdkTool(name) {
  return process.env.JAVA_HOME
    ? path.join(process.env.JAVA_HOME, 'bin', name + suffix)
    : name + suffix;
}

function run(command, args) {
  const result = spawnSync(command, args, { stdio: 'inherit', windowsHide: true });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(path.basename(command) + ' failed.');
}

function files(dir, suffix) {
  const found = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const item = path.join(dir, entry.name);
    if (entry.isDirectory()) found.push(...files(item, suffix));
    else if (entry.name.endsWith(suffix)) found.push(item);
  }
  return found;
}

const aapt = path.join(tools, 'aapt2' + suffix);
const zipalign = path.join(tools, 'zipalign' + suffix);
const d8 = path.join(tools, 'lib', 'd8.jar');
const signer = path.join(tools, 'lib', 'apksigner.jar');
for (const dependency of [platform, aapt, zipalign, d8, signer]) {
  if (!existsSync(dependency)) throw new Error('找不到所需的 SDK 元件：' + dependency);
}

const build = mkdtempSync(path.join(output, '.tmp-'));
try {
  const classes = path.join(build, 'classes');
  const dex = path.join(build, 'dex');
  const generated = path.join(build, 'generated');
  for (const dir of [classes, dex, generated]) mkdirSync(dir);

  const resources = path.join(build, 'resources.zip');
  const unsigned = path.join(build, 'unsigned.apk');
  const aligned = path.join(build, 'aligned.apk');
  const manifest = path.join(root, 'AndroidManifest.xml');
  const runtime = path.join(root, 'runtime');
  run(aapt, ['compile', '--dir', path.join(root, 'res'), '-o', resources]);
  run(aapt, ['link', '-o', unsigned, '--manifest', manifest, '-I', platform,
    '--java', generated, '-A', path.join(runtime, 'assets'), resources]);
  run(jdkTool('javac'), ['-encoding', 'UTF-8', '-source', '8', '-target', '8',
    '-classpath', platform, '-d', classes, ...files(path.join(root, 'src'), '.java'), ...files(generated, '.java')]);
  run(jdkTool('java'), ['-cp', d8, 'com.android.tools.r8.D8',
    '--lib', platform, '--min-api', '26', '--output', dex, ...files(classes, '.class')]);
  run(jdkTool('jar'), ['uf', unsigned, '-C', dex, 'classes.dex', '-C', runtime, 'lib']);
  run(zipalign, ['-f', '4', unsigned, aligned]);

  const key = path.join(output, 'development.keystore');
  if (!existsSync(key)) {
    run(jdkTool('keytool'), ['-genkeypair', '-keystore', key, '-storepass', 'android',
      '-keypass', 'android', '-alias', 'quota-pocket', '-keyalg', 'RSA', '-keysize', '2048',
      '-validity', '10000', '-dname', 'CN=Quota Pocket Development']);
  }
  const apk = path.join(output, 'ai-quota-pocket-development.apk');
  run(jdkTool('java'), ['-jar', signer, 'sign', '--v4-signing-enabled', 'false',
    '--ks', key, '--ks-pass', 'pass:android', '--ks-key-alias', 'quota-pocket', '--out', apk, aligned]);
  run(jdkTool('java'), ['-jar', signer, 'verify', '--verbose', apk]);
  console.log('APK: ' + apk);
} finally {
  rmSync(build, { recursive: true, force: true });
}
