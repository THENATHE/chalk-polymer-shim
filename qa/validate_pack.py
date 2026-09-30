#!/usr/bin/env python3
"""Validate a generated Chalk resource pack and its standalone shim release.

Uses only the Python standard library. Writes machine-readable evidence beside
the pack (or --output), prints the same JSON, and exits nonzero on any failure.
This is a static artifact check; multiplayer and rendering checks run separately.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import struct
import sys
import zipfile
import zlib

PREFIX = 'assets/chalk_polymer_compat/items/mark/'
COLORS = dict(zip(
    'white orange magenta light_blue yellow lime pink gray light_gray cyan purple blue brown green red black'.split(),
    [0xffffff, 0xe16201, 0xaa32a0, 0x258ac8, 0xf0ff15, 0x5faa19, 0xd6658f, 0x292929,
     0x8b8b8b, 0x157687, 0x641f9c, 0x2c2e8e, 0x613c20, 0x495b24, 0x8f2121, 0x171717]))
NORMALS = dict(up=(0, 1, 0), down=(0, -1, 0), east=(1, 0, 0), west=(-1, 0, 0),
               north=(0, 0, -1), south=(0, 0, 1))


def require(condition, message):
    if not condition:
        raise ValueError(message)


def asset(identifier, category, suffix='.json'):
    namespace, name = identifier.split(':', 1) if ':' in identifier else ('minecraft', identifier)
    require('..' not in name.split('/'), 'Unsafe resource identifier ' + identifier)
    return f'assets/{namespace}/{category}/{name}{suffix}'


def rotate(vector, x, y):
    """Blockstate clockwise X, then clockwise Y, matching the display quaternion."""
    a, b = math.radians(-x), math.radians(-y)
    vx, vy, vz = vector
    vy, vz = vy * math.cos(a) - vz * math.sin(a), vy * math.sin(a) + vz * math.cos(a)
    return vx * math.cos(b) + vz * math.sin(b), vy, -vx * math.sin(b) + vz * math.cos(b)


def png_check(data, name):
    require(data.startswith(b'\x89PNG\r\n\x1a\n'), 'Invalid PNG header: ' + name)
    pos, ids, compressed = 8, [], []
    while pos < len(data):
        size = struct.unpack('>I', data[pos:pos + 4])[0]
        kind = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + size]
        crc = struct.unpack('>I', data[pos + 8 + size:pos + 12 + size])[0]
        require(zlib.crc32(kind + chunk) & 0xffffffff == crc, 'PNG CRC error: ' + name)
        ids.append(kind)
        if kind == b'IDAT':
            compressed.append(chunk)
        pos += size + 12
    require(ids[0] == b'IHDR' and ids[-1] == b'IEND' and compressed, 'Incomplete PNG: ' + name)
    require(bool(zlib.decompress(b''.join(compressed))), 'Empty PNG pixels: ' + name)


def inspect(pack_path, chalk_path, shim_path):
    result = {}
    with zipfile.ZipFile(pack_path) as pack, zipfile.ZipFile(chalk_path) as chalk, zipfile.ZipFile(shim_path) as shim:
        for label, archive in [('pack', pack), ('chalk', chalk), ('shim', shim)]:
            require(archive.testzip() is None, 'ZIP CRC failure: ' + label)
            require(len(archive.namelist()) == len(set(archive.namelist())), 'Duplicate ZIP entries: ' + label)
        names = set(pack.namelist())
        # Deliberately strict: the generated pack must also fix upstream malformed JSON.
        documents = {name: json.loads(pack.read(name)) for name in sorted(names)
                     if name.endswith(('.json', '.mcmeta'))}
        result['pack_json_documents'] = len(documents)
        require('pack.mcmeta' in documents, 'Missing resource-pack metadata')
        require('licenses/chalk/LICENSE' in names, 'Missing served Chalk artwork license')
        require(b'DaFuqs' in pack.read('licenses/chalk/LICENSE'), 'Incomplete Chalk copyright notice')

        generated = {name: doc for name, doc in documents.items() if name.startswith(PREFIX)}
        blocks = sorted({name[len(PREFIX):].split('/')[0] for name in generated})
        white = {'white_chalk_mark', 'white_glow_chalk_mark'}
        all_blocks = {color + suffix for color in COLORS for suffix in ('_chalk_mark', '_glow_chalk_mark')}
        require(set(blocks) in (white, all_blocks), 'Unexpected active Chalk blocks: ' + str(blocks))
        require(len(generated) == len(blocks) * 12, 'Wrong generated model-definition count')
        checked_models, checked_textures, external = set(), set(), set()

        def check_texture(identifier):
            require(not identifier.startswith('#'), 'Unresolved texture variable: ' + identifier)
            path = asset(identifier, 'textures', '.png')
            if path not in names and path.startswith('assets/minecraft/'):
                external.add(path)
                return
            require(path in names, 'Missing texture: ' + path)
            if path not in checked_textures:
                data = pack.read(path)
                png_check(data, path)
                if path.startswith('assets/chalk/'):
                    require(data == chalk.read(path), 'Chalk artwork changed: ' + path)
                checked_textures.add(path)

        def check_model(identifier, visiting=None):
            path = asset(identifier, 'models')
            if path in checked_models:
                return
            if path not in names and path.startswith('assets/minecraft/'):
                external.add(path)
                return
            require(path in documents, 'Missing model: ' + path)
            visiting = set() if visiting is None else visiting
            require(path not in visiting, 'Model-parent cycle: ' + path)
            visiting.add(path)
            model = documents[path]
            if 'parent' in model:
                check_model(model['parent'], visiting)
            textures = model.get('textures', {})
            for key, value in textures.items():
                seen = {key}
                while value.startswith('#'):
                    key = value[1:]
                    require(key not in seen, 'Texture cycle: ' + path)
                    seen.add(key)
                    require(key in textures, 'Unresolved inherited Chalk texture: ' + path)
                    value = textures[key]
                check_texture(value)
            for element in model.get('elements', []):
                for face in element['faces'].values():
                    texture = face['texture']
                    if texture.startswith('#'):
                        require(texture[1:] in textures, 'Missing face texture: ' + path)
                    else:
                        check_texture(texture)
            visiting.remove(path)
            checked_models.add(path)

        for name, definition in documents.items():
            if name.startswith(('assets/chalk/items/', PREFIX)):
                model = definition['model']
                require(model['type'] in ('minecraft:model', 'model'), 'Unexpected custom item model: ' + name)
                check_model(model['model'])

        state_checks, normal_checks, expected_definitions = [], 0, set()
        for block in blocks:
            color = block.removesuffix('_chalk_mark').removesuffix('_glow')
            glow = '_glow_' in block
            blockstate_path = f'assets/chalk/blockstates/{block}.json'
            source = json.loads(chalk.read(blockstate_path))
            if blockstate_path in documents:
                require(documents[blockstate_path] == source, 'Altered original blockstate: ' + block)
            variants = source['variants']
            require(len(variants) == 54, 'Wrong source state count: ' + block)
            for facing in NORMALS:
                for orientation in range(9):
                    key = f'facing={facing},orientation={orientation}'
                    variant = variants[key]
                    model_id = variant['model']
                    model_path = asset(model_id, 'models')
                    model = documents[model_path]
                    require(model == json.loads(chalk.read(model_path)), 'Changed original mark model: ' + model_path)
                    generated_path = PREFIX + block + '/' + model_id.split(':', 1)[1] + '.json'
                    expected_definitions.add(generated_path)
                    definition = generated[generated_path]['model']
                    require(definition['model'] == model_id, 'Wrong source geometry: ' + generated_path)
                    require(definition['tints'] == [{'type': 'minecraft:constant', 'value': COLORS[color]}],
                            'Wrong constant color: ' + generated_path)
                    x, y = variant.get('x', 0), variant.get('y', 0)
                    require(x in (0, 90, 180, 270) and y in (0, 90, 180, 270), 'Unsupported source rotation')
                    for element in model['elements']:
                        if glow:
                            require(element.get('light_emission') == 15 and element.get('shade') is False,
                                    'Missing upstream emissive glow geometry')
                        for face in element['faces']:
                            actual = rotate(NORMALS[face], x, y)
                            require(all(abs(a - b) < 1e-6 for a, b in zip(actual, NORMALS[facing])),
                                    'Wrong transformed face: ' + block + ' ' + key)
                            normal_checks += 1
                    state_checks.append({'block': block, 'state': key, 'source_model': model_id, 'x': x, 'y': y})
        require(expected_definitions == set(generated), 'Missing or unused generated definitions')
        require(len(state_checks) in (108, 1728), 'Incorrect mapped state count')
        result.update(active_colors=len(blocks) // 2, active_blocks=len(blocks),
                      generated_item_definitions=len(generated), mapped_states=len(state_checks),
                      source_geometry_rotation_checks=normal_checks, resolved_models=len(checked_models),
                      verified_textures=len(checked_textures), vanilla_external_resources=sorted(external),
                      state_mapping_sha256=hashlib.sha256(json.dumps(state_checks, sort_keys=True).encode()).hexdigest())

        shim_names = shim.namelist()
        metadata = json.loads(shim.read('fabric.mod.json'))
        chalk_metadata = json.loads(chalk.read('fabric.mod.json'))
        require(metadata['id'] == 'chalk_polymer_compat', 'Wrong shim mod ID')
        require(metadata['environment'] == 'server', 'Shim must be server-only')
        require(metadata['depends']['chalk'] == chalk_metadata['version'], 'Wrong supported Chalk release')
        classes = [name for name in shim_names if name.endswith('.class')]
        require(classes and all(name.startswith('com/thenathe/chalkcompat/') for name in classes),
                'Foreign/upstream/QA classes in release JAR')
        require(not any(name.endswith(('.jar', '.zip')) for name in shim_names), 'Nested dependency/archive in shim')
        require(not any('qa/' in name.lower() or '/test/' in name.lower() for name in shim_names), 'QA files in shim')
        require('LICENSE' in shim_names and 'licenses/chalk-LICENSE.txt' in shim_names, 'Missing release license')
        require(pack.read('licenses/chalk/LICENSE') == shim.read('licenses/chalk-LICENSE.txt'), 'Mismatched artwork license')
        mixins = json.loads(shim.read('chalk_polymer_compat.mixins.json'))
        for mixin in mixins['mixins']:
            require(mixins['package'].replace('.', '/') + '/' + mixin + '.class' in shim_names,
                    'Missing configured mixin: ' + mixin)
        result.update(shim_version=metadata['version'], chalk_version=chalk_metadata['version'],
                      shim_classes=len(classes), packaged_mixins=len(mixins['mixins']),
                      standalone_shim=True, all_pack_json_valid=True, licenses_present=True)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('pack', 'chalk', 'shim'):
        parser.add_argument('--' + name, required=True, type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    report = {'passed': False, 'scope': 'Static pack, source-rotation, and release-JAR validation'}
    try:
        for name in ('pack', 'chalk', 'shim'):
            path = getattr(args, name).resolve()
            report[name] = {'path': str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
        report.update(inspect(args.pack, args.chalk, args.shim))
        report['passed'] = True
    except Exception as error:
        report['error'] = f'{type(error).__name__}: {error}'
    output = args.output or args.pack.with_name('pack-validation.json')
    output.parent.mkdir(parents=True, exist_ok=True)
    rendered = json.dumps(report, indent=2, sort_keys=True) + '\n'
    output.write_text(rendered)
    print(rendered, end='')
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    sys.exit(main())
