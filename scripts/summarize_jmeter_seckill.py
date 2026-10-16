"""Summarize JMeter CSV results without treating expected stock-out as a server fault."""
import argparse
from collections import Counter
import csv
import json
import math
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('results', type=Path)
    parser.add_argument('--baseline', type=Path)
    args = parser.parse_args()
    with args.results.open(encoding='utf-8-sig', newline='') as stream:
        rows = list(csv.DictReader(stream))
    if not rows: parser.error('No samples found')
    if not {'timeStamp', 'elapsed', 'responseMessage'} <= rows[0].keys():
        parser.error('Results must be CSV JTL with timestamps, elapsed time and responseMessage')
    categories = Counter()
    accepted = []
    for row in rows:
        text = row['responseMessage']
        parts = text.split('|')
        category = parts[0] if parts[0] in {'ACCEPTED', 'STOCK_OUT', 'DUPLICATE', 'BUSINESS_ERROR', 'HTTP_ERROR', 'UNEXPECTED_ERROR'} else 'UNCLASSIFIED'
        categories[category] += 1
        fields = dict(part.split('=', 1) for part in parts[1:] if '=' in part)
        if category == 'ACCEPTED':
            accepted.append({'userId': fields.get('userId'), 'orderId': fields.get('orderId')})
    durations = sorted(int(row['elapsed']) for row in rows)
    start = min(int(row['timeStamp']) for row in rows)
    end = max(int(row['timeStamp']) + int(row['elapsed']) for row in rows)
    seconds = max((end - start) / 1000, 0.001)
    def percentile(fraction): return durations[max(0, math.ceil(len(durations) * fraction) - 1)]
    summary = {'samples': len(rows), 'categories': dict(categories), 'sample_window_seconds': seconds,
               'http_requests_per_second_in_sample_window': round(len(rows) / seconds, 2),
               'accepted_requests_per_second_in_sample_window': round(len(accepted) / seconds, 2),
               'average_ms': round(sum(durations) / len(durations), 2), 'p95_ms': percentile(.95), 'p99_ms': percentile(.99),
               'duplicate_accepted_user_ids': len(accepted) - len({row['userId'] for row in accepted}),
               'duplicate_accepted_order_ids': len(accepted) - len({row['orderId'] for row in accepted}),
               'note': 'One burst is not sustained capacity. Accepted means reserved, not paid or necessarily persisted yet.'}
    if args.baseline:
        baseline = json.loads(args.baseline.read_text(encoding='utf-8'))
        summary['baseline_stock'] = baseline['redis_stock_before']
        summary['acceptance_exceeds_baseline_stock'] = len(accepted) > baseline['redis_stock_before']
        summary['expected_total_db_orders_after_settlement'] = baseline['mysql_orders_before'] + len(accepted)
    with args.results.with_name('accepted-orders.csv').open('w', encoding='utf-8', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=['userId', 'orderId'])
        writer.writeheader()
        writer.writerows(accepted)
    args.results.with_name('summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(summary, ensure_ascii=True, indent=2))


if __name__ == '__main__': main()
