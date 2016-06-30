package org.rust.lang.doc.lexer;

import com.intellij.lexer.*;
import com.intellij.psi.tree.IElementType;

import static org.rust.lang.doc.psi.RustDocElementTypes.*;
import static com.intellij.psi.TokenType.*;

%%

%{
  private int MAIN_STATE = YYINITIAL;

  public _RustDocHighlightingLexer(boolean isBlock) {
    this((java.io.Reader)null);
    MAIN_STATE = isBlock ? IN_BLOCK : IN_EOL;
  }

  // FIXME(jajakobyly): I think it is possible this will break, though I couldn't make it do so :-)
  private char CODE_FENCE_DELIM = '\0';

  private boolean isLastToken() {
    return zzMarkedPos == zzEndRead;
  }

  private void trimTrailingAsterisks() {
    int i = yylength() - 1;
    char ch = yycharat(i);

    // Trim trailing *****/ if we are at the end of block doc comment
    if (i >= 1 && MAIN_STATE == IN_BLOCK && isLastToken() && ch == '/' && yycharat(i - 1) == '*') {
      i--; // consume '/'
      ch = yycharat(i);
      while (i > 0 && ch == '*') {
        ch = yycharat(--i);
      }
    }

    yypushback(yylength() - i - 1);
  }

  private void docHeadingTrimRight() {
    trimTrailingAsterisks();

    int i = yylength() - 1;
    char ch = yycharat(i);

    // Trim trailing whitespace
    // We don't have to check whether i underflows over 0,
    // because we have guarantee that there is at least one '#'
    while (ch == ' ' || ch == '\t') {
      ch = yycharat(--i);
    }

    yypushback(yylength() - i - 1);
  }
%}

%public
%class _RustDocHighlightingLexer
%implements FlexLexer
%function advance
%type IElementType

%s IN_BLOCK
%s IN_EOL

%s IN_DOC_DATA
%s IN_DOC_DATA_DEEP

%s IN_CODE_FENCE
%s IN_CODE_FENCE_DECO_BLOCK
%s IN_CODE_FENCE_DECO_EOL

%unicode

///////////////////////////////////////////////////////////////////////////////////////////////////
// Whitespaces
///////////////////////////////////////////////////////////////////////////////////////////////////

EOL_WS           = \n | \r | \r\n
LINE_WS          = [\ \t]
WHITE_SPACE_CHAR = {EOL_WS} | {LINE_WS}

///////////////////////////////////////////////////////////////////////////////////////////////////
// Markdown/CommonMark macros
///////////////////////////////////////////////////////////////////////////////////////////////////

HEADING_HASH = "#"{1,6}

// http://spec.commonmark.org/0.25/#links
LINK_TEXT    = "[" ( [^\]\r\n] | "\\]" )* "]"

INLINE_LINK  = {LINK_TEXT} "(" ( [^\(\)\r\n] | "\\(" | "\\)" )* ")"
REF_LINK     = {LINK_TEXT} {LINK_TEXT}?
LINK_REF_DEF = {LINK_TEXT} ":" [^\r\n]*

// http://spec.commonmark.org/0.25/#code-spans
CODE_SPAN    = "`" ( [^`\r\n] | "`" "`"+ )* "`"

%%

<YYINITIAL> {
    "/*"    { assert MAIN_STATE == IN_BLOCK; yybegin(IN_BLOCK); yypushback(2); }
    "//"    { assert MAIN_STATE == IN_EOL;   yybegin(IN_EOL);   yypushback(2); }
}

///////////////////////////////////////////////////////////////////////////////////////////////////
// Block docs
///////////////////////////////////////////////////////////////////////////////////////////////////

<IN_BLOCK> {
    "/*"[*!]    { yybegin(IN_DOC_DATA); return DOC_DECO; }
    "*"+ "/"    { return (isLastToken() ? DOC_DECO : DOC_TEXT); }
    "*"         { yybegin(IN_DOC_DATA); return DOC_DECO; }
    [^\ \t]     { yybegin(IN_DOC_DATA); yypushback(1); }
}

///////////////////////////////////////////////////////////////////////////////////////////////////
// EOL docs
///////////////////////////////////////////////////////////////////////////////////////////////////

<IN_EOL> "//"[/!]   { yybegin(IN_DOC_DATA); return DOC_DECO; }

///////////////////////////////////////////////////////////////////////////////////////////////////
// Doc contents
///////////////////////////////////////////////////////////////////////////////////////////////////

<IN_DOC_DATA> {
    //== Headings
    //== http://spec.commonmark.org/0.25/#atx-headings
    {HEADING_HASH} " " [^\r\n]+     { yybegin(IN_DOC_DATA_DEEP); docHeadingTrimRight(); return DOC_HEADING; }
    {HEADING_HASH} [\ \r\n]         { yybegin(IN_DOC_DATA_DEEP); yypushback(1); return DOC_HEADING; }
    {HEADING_HASH}                  { if (isLastToken()) { return DOC_HEADING; }
                                      else { yybegin(IN_DOC_DATA_DEEP); return DOC_TEXT; } }

    {LINE_WS}+                      { return WHITE_SPACE; }
    [^]                             { yybegin(IN_DOC_DATA_DEEP); yypushback(1); }
}

<IN_DOC_DATA_DEEP> {
    "*"+ "/"            {
        if (MAIN_STATE == IN_BLOCK && isLastToken()) { yybegin(MAIN_STATE); yypushback(yylength()); }
        else { return DOC_TEXT; }
    }

    {INLINE_LINK}       { return DOC_INLINE_LINK; }
    {REF_LINK}          { return DOC_REF_LINK; }
    {LINK_REF_DEF}      { return DOC_LINK_REF_DEF; }
    {CODE_SPAN}         { return DOC_CODE_SPAN; }

    "```" | "~~~"       { CODE_FENCE_DELIM = yycharat(0);
                          yybegin(IN_CODE_FENCE);
                          return DOC_CODE_FENCE; }

    {EOL_WS}            { yybegin(MAIN_STATE); return WHITE_SPACE;}
    {LINE_WS}+          { return WHITE_SPACE; }

    [^]                 { return DOC_TEXT; }
}

///////////////////////////////////////////////////////////////////////////////////////////////////
// Code fences
///////////////////////////////////////////////////////////////////////////////////////////////////

<IN_CODE_FENCE_DECO_BLOCK> {
    "*"+ "/"    { return (isLastToken() ? DOC_DECO : DOC_CODE_FENCE); }
    "*"         { yybegin(IN_CODE_FENCE); return DOC_DECO; }
    [^\ \t]     { yybegin(IN_CODE_FENCE); yypushback(1); }
}

<IN_CODE_FENCE_DECO_EOL> "//"[/!]   { yybegin(IN_CODE_FENCE); return DOC_DECO; }

<IN_CODE_FENCE> {
    "```" | "~~~"       {
        if (yycharat(0) == CODE_FENCE_DELIM) { yybegin(IN_DOC_DATA_DEEP); }
        return DOC_CODE_FENCE;
    }

    {EOL_WS}            { yybegin(MAIN_STATE == IN_BLOCK ? IN_CODE_FENCE_DECO_BLOCK : IN_CODE_FENCE_DECO_EOL);
                          return WHITE_SPACE;}
    [^]                 { return DOC_CODE_FENCE; }
}

///////////////////////////////////////////////////////////////////////////////////////////////////
// Catch All
///////////////////////////////////////////////////////////////////////////////////////////////////

{WHITE_SPACE_CHAR}  { return WHITE_SPACE; }
[^]                 { return BAD_CHARACTER; }
