# Complete editable procedures from production_c.bcp
# request is a per-transport correlation; job is the stable production order identity.
# Procedure inputs/outputs and invocation wiring remain in the accompanying .bcp.

# Procedure implementation
TYPE LIST_KEYS LIST TEXT
TYPE target_type RECORD x INTEGER y INTEGER z INTEGER
TYPE packet_type RECORD kind TEXT version INTEGER target target_type request TEXT operation TEXT arguments ANY
TYPE match_type RECORD m BOOLEAN
TYPE context_type RECORD p packet_type r ANY
REG context context_type
REG packet packet_type
REG reply ANY
REG keys LIST_KEYS
REG key TEXT
REG index INTEGER
REG one INTEGER
REG flag BOOLEAN
REG left ANY
REG right ANY
REG kind TEXT
REG outcome match_type
XBUS.READ context x0
FIELD packet context p
FIELD reply context r
CONST keys ["version","code","request","target","kind"]
CONST one 1
LIST.LENGTH index keys
guard:
SUBTRACT_INTEGER index index one
LIST.GET key keys index
HAS.FIELD flag reply key
JFALSE flag done
LESS_INTEGER flag index one
JFALSE flag guard
compare:
FIELD left reply kind
CONST kind "production_reply"
VALUE.EQUALS flag left kind
JFALSE flag done
FIELD left reply target
FIELD right packet target
VALUE.EQUALS flag left right
JFALSE flag done
FIELD left reply version
FIELD right packet version
VALUE.EQUALS flag left right
JFALSE flag done
FIELD left reply request
FIELD right packet request
VALUE.EQUALS flag left right
done:
RECORD.MAKE outcome m flag
XBUS.WRITE x0 outcome
