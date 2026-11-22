; Explicit named-entry write service. x0: {entry, v, expected}; x1: entry_coordinator.
; The original expected version is never replaced by the coordinator's fresh stat.
; Every completed request returns {ok,value}; a later request is a new caller operation, not a retry.
TYPE Request RECORD entry TEXT v ANY expected ANY
TYPE Kick RECORD entry TEXT
TYPE Result RECORD ok BOOLEAN value ANY
TYPE Mutation RECORD op TEXT volume TEXT entry TEXT expected ANY kind TEXT value ANY
REG request Request
REG kick Kick
REG result Result
REG mutation Mutation
REG entry TEXT
REG replacement ANY
REG expected ANY
REG snapshot ANY
REG actual ANY
REG version ANY
REG medium ANY
REG old_medium ANY
REG flag BOOLEAN
REG operation TEXT
REG kind TEXT
next:
XBUS.READ request x0
FIELD entry request entry
FIELD replacement request v
FIELD expected request expected
RECORD.MAKE kick entry entry
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
FIELD actual version entry
VALUE.EQUALS flag actual entry
JFALSE flag mismatch
FIELD medium version medium
FIELD old_medium expected medium
VALUE.EQUALS flag medium old_medium
JFALSE flag mismatch
JMP done
mismatch:
CONST result {"ok":false,"value":"archive_readback_mismatch"}
done:
XBUS.WRITE x0 result
JMP next
