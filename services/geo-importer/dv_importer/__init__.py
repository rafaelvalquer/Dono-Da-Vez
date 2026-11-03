"""Importador geográfico do Dono da Vez.

Nunca modifica carteiras, preços de aquisição ou titularidades: escreve somente em geo.staging_*
e aciona as funções geo_api.* (validação e publicação atômica no banco).
"""
