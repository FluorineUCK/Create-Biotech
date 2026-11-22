; One explicit archive write. x0 receives {v: candidate ledger, expected: original read version}.
; x1 connects to cluster_journal_coordinator.x1, then its existing Library adapter and serial owners.
; A later stat NEVER replaces the original version; stale candidates must fail, not erase a newer ledger.
TYPE Request RECORD v ANY expected ANY
TYPE Kick RECORD v INTEGER
TYPE Result RECORD ok BOOLEAN value ANY
TYPE Mutation RECORD op TEXT volume TEXT entry TEXT expected ANY kind TEXT value ANY
REG request Request
REG kick Kick
REG result Result
REG mutation Mutation
REG replacement ANY
REG expected ANY
REG snapshot ANY
REG actual ANY
REG version ANY
REG medium ANY
REG old_medium ANY
REG flag BOOLEAN
REG zero INTEGER
REG operation TEXT
REG kind TEXT
XBUS.READ request x0
FIELD replacement request v
FIELD expected request expected
CONST zero 0
RECORD.MAKE kick v zero
XBUS.WRITE x1 kick
XBUS.READ result x1
FIELD flag result ok
JFALSE flag done
CONST operation "put"
CONST kind "DATA"
RECORD.MAKE mutation op operation volume volume entry entry expected expected kind kind value replacement
XBUS.WRITE x1 mutation
XBUS.READ result x1
FIELD flag result ok
JFALSE flag done
FIELD snapshot result value
FIELD actual snapshot value
VALUE.EQUALS flag actual replacement
JFALSE flag mismatch
FIELD version snapshot version
FIELD medium version medium
FIELD old_medium expected medium
VALUE.EQUALS flag medium old_medium
JFALSE flag mismatch
JMP done
mismatch:
CONST actual "archive_readback_mismatch"
RECORD.MAKE result ok flag value actual
done:
XBUS.WRITE x0 result
