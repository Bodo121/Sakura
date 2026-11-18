/// Bounded arithmetic parser. No dynamic evaluation, shell or network.
double calculate(String input) {
  if (input.length > 256) throw const FormatException('Expression too long');
  final parser = _Parser(
    input
        .replaceAll('×', '*')
        .replaceAll('÷', '/')
        .replaceAll('−', '-')
        .replaceAll(' ', ''),
  );
  final value = parser.expression();
  if (parser.at != parser.text.length || !value.isFinite)
    throw const FormatException('Invalid expression');
  return value;
}

class _Parser {
  _Parser(this.text);
  final String text;
  int at = 0;
  bool eat(String token) {
    if (at < text.length && text[at] == token) {
      at++;
      return true;
    }
    return false;
  }

  double expression() {
    var value = term();
    while (at < text.length) {
      if (eat('+')) {
        value += term();
      } else if (eat('-')) {
        value -= term();
      } else {
        break;
      }
    }
    return value;
  }

  double term() {
    var value = number();
    while (at < text.length) {
      if (eat('*')) {
        value *= number();
      } else if (eat('/')) {
        final n = number();
        if (n == 0) throw const FormatException('Division by zero');
        value /= n;
      } else {
        break;
      }
    }
    return value;
  }

  double number() {
    if (eat('+')) return number();
    if (eat('-')) return -number();
    if (eat('(')) {
      final value = expression();
      if (!eat(')')) throw const FormatException('Missing parenthesis');
      return value;
    }
    final begin = at;
    while (at < text.length && RegExp(r'[0-9.]').hasMatch(text[at])) {
      at++;
    }
    if (begin == at) throw const FormatException('Number expected');
    return double.parse(text.substring(begin, at));
  }
}
