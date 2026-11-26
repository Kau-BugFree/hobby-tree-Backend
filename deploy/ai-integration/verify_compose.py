"""Validate the rendered, non-secret production candidate before use."""
import ipaddress
import json
import re
import sys


def verify(config):
    services = config['services']
    assert set(services) == {'catalog-db', 'item-api', 'item-worker', 'item-sync'}
    assert config['networks']['catalog']['internal'] is True
    for name, service in services.items():
        assert re.fullmatch(r'[a-z0-9./_-]+@sha256:[0-9a-f]{64}', service['image'])
        assert service['pull_policy'] == 'never'
        assert int(service['mem_limit']) == int(service['memswap_limit'])
        assert 0 < int(service['mem_limit']) <= 512 * 1024**2
        assert set(service['networks']) == {'catalog'}
        env = service.get('environment', {})
        assert not set(env) & {'SERVICE_TOKEN', 'PGPASSWORD', 'POSTGRES_PASSWORD', 'ADPICK_API_KEY'}
        if name != 'catalog-db':
            assert service['read_only'] and service['user'] == '10001:10001'
            assert 'ALL' in service['cap_drop']
            assert 'no-new-privileges:true' in service['security_opt']
            assert env['ADPICK_STORAGE_APPROVED'] == env['ADPICK_FILTERING_APPROVED'] == 'false'
        if name != 'item-api':
            assert not service.get('ports')
    port, = services['item-api']['ports']
    ip = ipaddress.IPv4Address(port['host_ip'])
    assert any(ip in ipaddress.ip_network(n) for n in ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16'))
    assert port['target'] == 8001 and str(port['published']) == '8001'
    assert services['item-worker']['profiles'] == ['collection']
    assert services['item-sync']['profiles'] == ['sync']
    assert services['item-worker']['restart'] == services['item-sync']['restart'] == 'no'
    assert services['catalog-db']['volumes'][0]['bind']['create_host_path'] is False
    for value in config['secrets'].values():
        assert set(value) <= {'file', 'name'} and value.get('file', '').startswith('/')


if __name__ == '__main__':
    try:
        verify(json.load(sys.stdin))
        print('CANDIDATE_VALID_NOT_APPROVED_FOR_INSTALL')
    except Exception:
        raise SystemExit('CANDIDATE_INVALID') from None
