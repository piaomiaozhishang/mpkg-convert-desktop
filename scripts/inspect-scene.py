"""Inspect package JSON without extracting untrusted archive paths."""
import json
import struct
import sys
from pathlib import Path

for name in sys.argv[1:]:
    data = Path(name).read_bytes()
    pos = 0
    def integer():
        global pos
        value, = struct.unpack_from('<I', data, pos)
        pos += 4
        return value
    def string():
        global pos
        size = integer()
        value = data[pos:pos+size].decode('utf-8')
        pos += size
        return value
    magic = string()
    entries = [(string(), integer(), integer()) for _ in range(integer())]
    print(name, magic, len(entries))
    for path, offset, size in entries:
        if path in ('shaders/effects/opacity.frag', 'shaders/effects/opacity.vert', 'effects/opacity/effect.json'):
            print(path, data[pos+offset:pos+offset+size].decode('utf-8'))
        if path.endswith('.json') and (path == 'scene.json' or path.startswith(('models/', 'materials/'))):
            value = json.loads(data[pos+offset:pos+offset+size])
            if path == 'scene.json':
                print('GENERAL', json.dumps(value.get('general'), ensure_ascii=False))
                for obj in value.get('objects', []):
                    print('OBJECT', json.dumps({k: v for k, v in obj.items() if k != 'effects'}, ensure_ascii=False))
                    for effect in obj.get('effects', []):
                        print('EFFECT', json.dumps(effect, ensure_ascii=False))
            else:
                print(path, json.dumps(value, ensure_ascii=False))
    print('ASSETS', [(p, s) for p, _, s in entries if not p.endswith('.json')])
