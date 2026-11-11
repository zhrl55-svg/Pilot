#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""只读增量复评差异器。

按一级/二级章节切分被评规格，计算章节指纹，与冻结基线对比，
输出受影响章节与受影响模块，便于只重判受影响条目。

只读约束：不修改被评规格，不修改历史报告。

用法：
  python scripts/review_diff.py                          # 与冻结基线对比
  python scripts/review_diff.py --init                    # 首次建立基线
  python scripts/review_diff.py --out review/reports/change-log.md
"""

import argparse
import hashlib
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_SPEC = os.path.join(ROOT, '.codeartsdoer', 'specs', 'pilot_system_agent', 'spec.md')
DEFAULT_BASELINE = os.path.join(ROOT, 'review', 'snapshots', 'v1', 'section-fingerprints.md')

HEADING = re.compile(r'^(#{1,2})\s+(.+)$')


def split_sections(text):
    lines = text.splitlines()
    sections = []
    cur_level, cur_title, buf = None, None, []
    for line in lines:
        m = HEADING.match(line)
        if m:
            if cur_title is not None:
                sections.append((cur_level, cur_title, '\n'.join(buf)))
            cur_level, cur_title = len(m.group(1)), m.group(2).strip()
            buf = [line]
        else:
            buf.append(line)
    if cur_title is not None:
        sections.append((cur_level, cur_title, '\n'.join(buf)))
    return sections


def fingerprint(body):
    return hashlib.sha256(body.encode('utf-8')).hexdigest()[:12]


def normalize(title):
    return re.sub(r'[\s\.#]', '', title)


def load_baseline(path):
    base = {}
    if not os.path.exists(path):
        return base
    with open(path, 'r', encoding='utf-8') as f:
        for line in f:
            cells = [c.strip() for c in line.strip().strip('|').split('|')]
            if len(cells) < 2:
                continue
            fp = cells[-1].strip('`')
            if re.fullmatch(r'[0-9a-f]{12}', fp):
                base[normalize(cells[0])] = fp
    return base


def write_baseline(path, current):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8') as f:
        f.write('# 章节指纹基线 v1\n\n')
        f.write('> 生成方式：`python scripts/review_diff.py --init`（只读计算）\n')
        f.write('> 指纹定义：章节正文（含标题行）SHA-256 前 12 位\n\n')
        f.write('| 章节 | 指纹 |\n|---|---|\n')
        for title, fp in current.items():
            f.write('| %s | `%s` |\n' % (title, fp))


def module_of(title):
    m = re.match(r'5\.(\d+)', title)
    if m:
        return '5.' + m.group(1)
    m = re.match(r'4\.(\d+)', title)
    if m:
        return 'DFX-4.' + m.group(1)
    m = re.match(r'6\.(\d+)', title)
    if m:
        return 'DATA-6.' + m.group(1)
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--spec', default=DEFAULT_SPEC)
    ap.add_argument('--baseline', default=DEFAULT_BASELINE)
    ap.add_argument('--init', action='store_true')
    ap.add_argument('--out', default='')
    args = ap.parse_args()

    if not os.path.exists(args.spec):
        sys.stderr.write('被评规格不存在：%s\n' % args.spec)
        return 2

    with open(args.spec, 'r', encoding='utf-8') as f:
        text = f.read()

    current = {}
    for level, title, body in split_sections(text):
        if level <= 2:
            current[title] = fingerprint(body)

    if args.init or not os.path.exists(args.baseline):
        write_baseline(args.baseline, current)
        print('基线已建立：%s（%d 个章节）' % (os.path.relpath(args.baseline, ROOT), len(current)))
        return 0

    base = load_baseline(args.baseline)

    rows = []
    for title, fp in current.items():
        old = base.get(normalize(title))
        mod = module_of(title) or '全局'
        if old is None:
            rows.append((title, '新增', '—', fp, mod))
        elif old != fp:
            rows.append((title, '修改', old, fp, mod))
        else:
            rows.append((title, '维持', old, fp, mod))

    statuses = {'新增', '修改'}
    changed = [r for r in rows if r[1] in statuses]

    out = ['# 增量复评差异结果', '', '> 生成方式：只读脚本 `scripts/review_diff.py`', '',
           '| 章节 | 变更 | 旧指纹 | 新指纹 | 受影响模块 |', '|---|---|---|---|---|']
    for r in rows:
        out.append('| %s | %s | %s | %s | %s |' % r)
    out.append('')
    out.append('## 结论')
    out.append('')
    out.append('- 受影响章节数：%d' % len(changed))
    if not changed:
        out.append('- 结论：无变更，全部条目标注"维持"')
    else:
        mods = sorted({r[4] for r in changed})
        out.append('- 受影响范围：%s' % '、'.join(mods))
        unmapped = [r for r in changed if r[4] == '全局']
        if unmapped:
            out.append('- 无法映射章节：%d → 按 `E-DIFF-01` 退化为全量复评' % len(unmapped))
        else:
            out.append('- 可按模块映射只重判受影响条目，其余"维持"（T2，≤4 小时）')

    content = '\n'.join(out) + '\n'
    if args.out:
        if os.path.dirname(args.out):
            os.makedirs(os.path.dirname(args.out), exist_ok=True)
        with open(args.out, 'w', encoding='utf-8') as f:
            f.write(content)
        print('差异结果已写入 %s' % args.out)
    else:
        print(content)
    return 0


if __name__ == '__main__':
    sys.exit(main())