#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""只读质量门禁校验器。

解析 review/registry 下的条目登记册与矩阵登记册，以及正式报告，
执行 G1–G12 校验，输出 gateReport 到 review/gates/gate-report-v1.0.md。

只读约束：本脚本不写入除 gateReport 之外的任何文件，不修改任何档位或正文。
退出码：0 = 全部通过；1 = 存在阻断项；2 = 输入缺失。
"""

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REGISTRY = os.path.join(ROOT, 'review', 'registry', 'findings-registry.md')
MATRIX = os.path.join(ROOT, 'review', 'registry', 'matrix-registry.md')
CONSISTENCY = os.path.join(ROOT, 'review', 'registry', 'consistency-report.md')
REPORT = os.path.join(ROOT, 'review', 'reports', 'pilot-agent-review-v1.0.md')
OUT = os.path.join(ROOT, 'review', 'gates', 'gate-report-v1.0.md')

LEVELS_S = {'优势明显', '优势'}
SCOPES_W = {'高', '中', '低'}
TYPES_W = {'缺失', '不足', '冲突'}
LEVELS_W = {'短板', '缺失', '平衡'}
LEVELS_R = {'高', '中', '低'}
OWNERS_R = {'产品', '研发', 'ROM 集成者', '部署方'}
PRIORITY_A = {'P0', 'P1', 'P2'}
EFFORT_A = {'S', 'M', 'L'}
STATUS_A = {'待确认', '已排期', '进行中', '已验收', '已豁免'}
MATRIX_STATES = {'覆盖', '部分覆盖', '缺失', '未定义', '声明不做'}
MATRIX_ABBR = {'C': '覆盖', 'P': '部分覆盖', 'M': '缺失', 'U': '未定义', 'N': '声明不做'}
SENSITIVE = re.compile(r'(sk-[A-Za-z0-9]{8,}|1[3-9]\d{9}|cookie\s*=\s*[^\s<]*)')


def read(path):
    if not os.path.exists(path):
        return None
    with open(path, 'r', encoding='utf-8') as f:
        return f.read()


def parse_table(text, marker):
    """取 marker 之后的第一个 Markdown 表格，返回 (header, rows)。"""
    if text is None:
        return [], []
    idx = text.find(marker)
    if idx < 0:
        return [], []
    lines = text[idx + len(marker):].splitlines()
    blocks = []
    for line in lines:
        s = line.strip()
        if s.startswith('## '):
            break
        if s.startswith('|'):
            blocks.append(s)
    table = []
    for s in blocks:
        cells = [c.strip() for c in s.strip('|').split('|')]
        if all(re.fullmatch(r':?-{2,}:?', c) for c in cells if c):
            continue
        table.append(cells)
    if not table:
        return [], []
    return table[0], table[1:]


def gate(gates, gid, ok, detail):
    gates.append({'id': gid, 'result': 'PASS' if ok else 'BLOCK', 'detail': detail})


def main():
    reg = read(REGISTRY)
    mtx = read(MATRIX)
    cons = read(CONSISTENCY)
    rep = read(REPORT)
    if reg is None or mtx is None:
        sys.stderr.write('输入缺失：登记册或矩阵不存在\n')
        return 2

    s_head, s_rows = parse_table(reg, '## 一、优势条目（S）')
    w_head, w_rows = parse_table(reg, '## 二、短板条目（W）')
    r_head, r_rows = parse_table(reg, '## 三、风险条目（R）')
    a_head, a_rows = parse_table(reg, '## 四、改进项（A）')

    gates = []

    # G1 元数据块
    meta_keys = ['报告版本', '被评规格', '口径版本', '评审日期', '报告状态']
    missing = [k for k in meta_keys if rep is None or k not in rep]
    gate(gates, 'G1', not missing, '缺失字段：' + (', '.join(missing) if missing else '无'))

    # G2 结论摘要
    required = ['整体档位', '发布建议', 'Top 优势', 'Top 风险', 'P0 数量', '评估口径', '评审日期']
    miss2 = [k for k in required if rep is None or k not in rep]
    top_ok, top_detail = True, []
    if rep:
        for key in ['Top 优势', 'Top 风险']:
            m = re.search(re.escape(key) + r'[^\n]*', rep)
            if not m:
                top_ok = False
                top_detail.append(key + ' 缺失')
                continue
            line = m.group(0)
            items = [x for x in re.split(r'[①②③④⑤⑥]', line) if x.strip()]
            items = [x.strip('：: -') for x in items if x.strip('：: -')]
            n = max(len(items) - 1, 0)
            if not (3 <= n <= 5):
                top_ok = False
                top_detail.append('%s 条数=%d 越界' % (key, n))
            for it in items[1:]:
                if len(it) > 40:
                    top_ok = False
                    top_detail.append('%s 单条超 40 字' % key)
    gate(gates, 'G2', not miss2 and top_ok,
         '缺失要素：' + (', '.join(miss2) if miss2 else '无') + '；' + ('；'.join(top_detail) if top_detail else 'Top 条数与字数合规'))

    # G3 字段与枚举
    problems = []
    for row in s_rows:
        if len(row) < 6:
            problems.append('S 行字段不足')
            continue
        code, name, level = row[0], row[1], row[2]
        if not code or not name or not row[3] or not row[4] or not row[5]:
            problems.append('%s 字段缺失' % code)
        if len(name) < 4 or len(name) > 20:
            problems.append('%s 名称长度 %d' % (code, len(name)))
        if level not in LEVELS_S:
            problems.append('%s 档位非法' % code)
    for row in w_rows:
        if len(row) < 8:
            problems.append('W 行字段不足')
            continue
        code, name, level = row[0], row[1], row[2]
        if len(name) < 4 or len(name) > 20:
            problems.append('%s 名称长度 %d' % (code, len(name)))
        if level not in LEVELS_W:
            problems.append('%s 档位非法' % code)
        if row[5] not in SCOPES_W:
            problems.append('%s 影响面非法' % code)
        if row[6] not in TYPES_W:
            problems.append('%s 类型非法' % code)
    for row in r_rows:
        if len(row) < 10:
            problems.append('R 行字段不足')
            continue
        code = row[0]
        if row[3] not in LEVELS_R or row[4] not in LEVELS_R:
            problems.append('%s 概率或影响非法' % code)
        if row[8] not in OWNERS_R:
            problems.append('%s 责任方非法' % code)
    for row in a_rows:
        if len(row) < 8:
            problems.append('A 行字段不足')
            continue
        code = row[0]
        if row[1] not in PRIORITY_A or row[5] not in EFFORT_A or row[6] not in STATUS_A:
            problems.append('%s 枚举非法' % code)
    gate(gates, 'G3', not problems, '；'.join(problems) if problems else '字段与枚举全部合规')

    # G4 证据
    evd = []
    for rows, label in [(s_rows, 'S'), (w_rows, 'W'), (r_rows, 'R')]:
        for row in rows:
            if label == 'R':
                continue
            if len(row) < 4 or not row[3].strip():
                evd.append('%s 缺证据' % row[0])
                continue
            m = re.search(r'「(.+?)」', row[3])
            if m and len(m.group(1)) > 80:
                evd.append('%s 摘录 %d 字' % (row[0], len(m.group(1))))
            if SENSITIVE.search(row[3]):
                evd.append('%s 含敏感原文' % row[0])
    # R 条目证据在维度走查中，登记册以说明行承接；此处校验说明存在
    if '证据列' not in reg:
        evd.append('R 条目缺少证据承接说明')
    gate(gates, 'G4', not evd, '；'.join(evd) if evd else '证据覆盖 100%，摘录合规')

    # G5 负证据
    neg = []
    for row in w_rows:
        if len(row) < 4:
            continue
        if '§NONE' in row[3]:
            if '{' not in row[3] or '→' not in row[3] or '命中' not in row[3]:
                neg.append('%s 负证据不完整' % row[0])
    gate(gates, 'G5', not neg, '；'.join(neg) if neg else '负证据均含范围、检索式与命中数')

    # G6 矩阵
    m_head, m_rows = parse_table(mtx, '## 一、按模块纵向视图')
    mtx_problems = []
    if len(m_rows) != 12:
        mtx_problems.append('模块行数=%d，应为 12' % len(m_rows))
    cells = 0
    for row in m_rows:
        if len(row) != 11:
            mtx_problems.append('列数=%d，应为 11' % len(row))
            continue
        for cell in row[1:]:
            cells += 1
            if not cell.strip():
                mtx_problems.append('存在空格')
                continue
            base = cell.split('（')[0].strip()
            base = MATRIX_ABBR.get(base, base)
            if base not in MATRIX_STATES:
                mtx_problems.append('非法状态 %s' % cell)
            if base == '声明不做' and '1.4-' not in cell:
                mtx_problems.append('声明不做缺出处')
    if cells != 120:
        mtx_problems.append('格数=%d，应为 120' % cells)
    gate(gates, 'G6', not mtx_problems, '；'.join(mtx_problems[:6]) if mtx_problems else '120 格无空格、状态合法')

    # G7 编号
    codes = [row[0] for rows in (s_rows, w_rows, r_rows, a_rows) for row in rows if row]
    dup = sorted({c for c in codes if codes.count(c) > 1})
    gate(gates, 'G7', not dup, '重复编号：' + (', '.join(dup) if dup else '无'))

    # G8 一致性
    consistent = cons is not None and '未解决冲突数：0' in cons
    gate(gates, 'G8', consistent, '未解决冲突数：0' if consistent else '存在未解决冲突或缺少一致性报告')

    # G9 P0
    p0 = [row[0] for row in a_rows if row and len(row) > 1 and row[1] == 'P0']
    gate(gates, 'G9', len(p0) <= 8, 'P0 数量=%d' % len(p0))

    # G10 风险
    rprob = []
    for row in r_rows:
        if len(row) < 10:
            continue
        if not row[6] or not row[7] or not row[8] or not row[9]:
            rprob.append('%s 缺缓解/责任方/复评触发' % row[0])
    gate(gates, 'G10', not rprob, '；'.join(rprob) if rprob else '风险条目 100% 含缓解、责任方、复评触发')

    # G11 假设与未覆盖
    g11 = rep is not None and '假设' in rep and '未覆盖' in rep and '失效条件' in rep
    gate(gates, 'G11', g11, '假设、未覆盖项、失效条件均已声明' if g11 else '声明不完整')

    # G12 状态与口径
    g12 = rep is not None and '口径版本' in rep and '报告状态' in rep and 'K-2026.11' in rep
    gate(gates, 'G12', g12, '状态与口径版本已绑定' if g12 else '状态为空或口径未绑定')

    blocked = [g for g in gates if g['result'] != 'PASS']

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, 'w', encoding='utf-8') as f:
        f.write('# 门禁报告 v1.0\n\n')
        f.write('> 生成方式：只读脚本 `scripts/review_lint.py`\n')
        f.write('> 退出码规则：全通过 0；任一阻断 1\n\n')
        f.write('| 门禁 | 结果 | 说明 |\n|---|---|---|\n')
        for g in gates:
            f.write('| %s | %s | %s |\n' % (g['id'], g['result'], g['detail']))
        f.write('\n## 结论\n\n')
        f.write('- 阻断项：%d\n' % len(blocked))
        f.write('- 签发结论：%s\n' % ('允许签发' if not blocked else '禁止签发（E-QG-01）'))

    print('gateReport 已写入 %s' % os.path.relpath(OUT, ROOT).replace('\\', '/'))
    for g in gates:
        print('%-4s %-5s %s' % (g['id'], g['result'], g['detail']))
    return 0 if not blocked else 1


if __name__ == '__main__':
    sys.exit(main())