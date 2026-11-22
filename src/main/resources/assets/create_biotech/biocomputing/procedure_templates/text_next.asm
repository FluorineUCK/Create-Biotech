; General UTF-16 text iteration, Assembly v1 / ISA-1a. No ANY or address policy.
; offset is a zero-based UTF-16 cursor, not a codepoint ordinal.
; done=true at end: next=offset, codepoint=-1, whitespace/control=false.
; Otherwise next advances one codepoint; whitespace=Java isWhitespace, control=isISOControl.
; Invalid bounds or splitting a surrogate pair faults without publishing result.
; Iterate explicitly with next. Source stays immutable. Import/edit is not installation.
REG result TEXT_NEXT
TEXT.NEXT result text offset
RET
