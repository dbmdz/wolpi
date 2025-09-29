"""
API-compatible wrapper around pyvips to make iiif-validator run inside Wolpi
using vips-ffm instead of pyvips.

This is a partial implementation, only the methods and operators used by
iiif-validator are implemented. A more generic implementation would be possible
by re-implementing `voperation` from pyvips in terms of vips-ffm instead of
gobject-introspection, but that would be more work than we want to invest right
now.
"""
from __future__ import annotations

import java

# Import the various Java types involved
Arena = java.type("java.lang.foreign.Arena")
VImage = java.type("app.photofox.vipsffm.VImage")
VipsOption = java.type("app.photofox.vipsffm.VipsOption")
VipsOperationRelational = java.type(
    "app.photofox.vipsffm.enums.VipsOperationRelational"
)
VipsOperationBoolean = java.type(
  "app.photofox.vipsffm.enums.VipsOperationBoolean")
VipsOperationRound = java.type("app.photofox.vipsffm.enums.VipsOperationRound")
VipsAngle = java.type("app.photofox.vipsffm.enums.VipsAngle")
VipsSize = java.type("app.photofox.vipsffm.enums.VipsSize")
VipsDirection = java.type("app.photofox.vipsffm.enums.VipsDirection")
WolpiVipsHelper = java.type("dev.mdz.wolpi.extension.WolpiVipsHelper")

# Helper type aliases for the overlads
type RelationalOther = Image | int | float | list[int | float]
type BooleanOther = Image | int | float | list[int | float]


class Image:
  @classmethod
  def new_from_buffer(cls, buffer: bytes, options: str = "") -> Image:
    arena = Arena.ofAuto()
    vimg = VImage.newFromBytes(arena, buffer)
    return cls(arena, vimg)

  @classmethod
  def new_from_file(cls, filename: str) -> Image:
    arena = Arena.ofAuto()
    vimg = VImage.newFromFile(arena, filename)
    return cls(arena, vimg)

  def __init__(self, arena, vimg):
    self.arena = arena
    self.vimg = vimg

  @property
  def width(self) -> int:
    return self.vimg.getWidth()

  @property
  def height(self) -> int:
    return self.vimg.getHeight()

  @property
  def format(self) -> str:
    return WolpiVipsHelper.image_get_band_format(self.vimg).getNickname()

  def extract_area(self, left: int, top: int, width: int, height: int) -> Image:
    vimg2 = self.vimg.extractArea(left, top, width, height)
    return Image(self.arena, vimg2)

  def flip(self, direction: str) -> Image:
    if direction == "horizontal":
      vimg2 = self.vimg.flip(VipsDirection.DIRECTION_HORIZONTAL)
    elif direction == "vertical":
      vimg2 = self.vimg.flip(VipsDirection.DIRECTION_VERTICAL)
    else:
      raise ValueError("direction must be 'horizontal' or 'vertical'")
    return Image(self.arena, vimg2)

  def abs(self) -> float:
    return self.vimg.abs()

  def max(self) -> float:
    return self.vimg.max()

  def get(self, field: str) -> str:
    return self.vimg.getString(field)

  def hasalpha(self) -> bool:
    return self.vimg.hasAlpha()

  def bandjoin(self, images: list[Image]) -> Image:
    return VImage.bandjoin(self.arena, [i.vimg for i in images])

  def avg(self) -> float:
    return self.vimg.avg()

  def rotate(self, angle: float) -> Image:
    vimg2 = self.vimg.rotate(angle)
    return Image(self.arena, vimg2)

  def rot90(self) -> Image:
    vimg2 = self.vimg.rot(VipsAngle.ANGLE_D90)
    return Image(self.arena, vimg2)

  def rot180(self) -> Image:
    vimg2 = self.vimg.rot(VipsAngle.ANGLE_D180)
    return Image(self.arena, vimg2)

  def rot270(self) -> Image:
    vimg2 = self.vimg.rot(VipsAngle.ANGLE_D270)
    return Image(self.arena, vimg2)

  def thumbnail_image(self, width: int, height: int | None = None,
      size: str | None = None) -> Image:
    args = []
    if height is not None:
      args.append(VipsOption.Int("height", height));
    if size is not None:
      for val in VipsSize.values():
        if val.getNickname() == size:
          args.append(VipsOption.Enum("size", val))
          break
    vimg2 = self.vimg.thumbnailImage(width, *args)
    return Image(self.arena, vimg2)

  def _bandbool(self, op: type[VipsOperationBoolean]) -> Image:
    vimg2 = self.vimg.bandbool(op)
    return Image(self.arena, vimg2)

  def bandand(self) -> Image:
    op = VipsOperationBoolean.OPERATION_BOOLEAN_AND
    return self._bandbool(op)

  def bandor(self) -> Image:
    op = VipsOperationBoolean.OPERATION_BOOLEAN_OR
    return self._bandbool(op)

  def bandeor(self) -> Image:
    op = VipsOperationBoolean.OPERATION_BOOLEAN_EOR
    return self._bandbool(op)

  @property
  def bands(self) -> int:
    return WolpiVipsHelper.image_get_bands(self.vimg)

  @property
  def interpretation(self) -> str:
    return WolpiVipsHelper.image_get_interpretation(self.vimg).getNickname()

  def __sub__(self, other: Image | int | float | list[int | float]) -> Image:
    if isinstance(other, Image):
      vimg2 = self.vimg.subtract(other.vimg)
    elif isinstance(other, list):
      vimg2 = self.vimg.linear([1], [-x for x in other])
    else:
      vimg2 = self.vimg.linear([1], [-other])
    return Image(self.arena, vimg2)

  def __add__(self, other: Image | int | float | list[int | float]) -> Image:
    if isinstance(other, Image):
      vimg2 = self.vimg.add(other.vimg)
    elif isinstance(other, list):
      vimg2 = self.vimg.linear([1], other)
    else:
      vimg2 = self.vimg.linear([1], [other])
    return Image(self.arena, vimg2)

  def __mul__(self, other: Image | int | float | list[int | float]) -> Image:
    if isinstance(other, Image):
      vimg2 = self.vimg.multiply(other.vimg)
    elif isinstance(other, list):
      vimg2 = self.vimg.linear(other, [0])
    else:
      vimg2 = self.vimg.linear([other], [0])
    return Image(self.arena, vimg2)

  def __div__(self, other: Image | int | float | list[int | float]) -> Image:
    if isinstance(other, Image):
      vimg2 = self.vimg.divide(other.vimg)
    elif isinstance(other, list):
      vimg2 = self.vimg.linear([1 / x for x in other], [0])
    else:
      vimg2 = self.vimg.linear([1 / other], [0])
    return Image(self.arena, vimg2)

  def __floordiv__(self,
      other: Image | int | float | list[int | float]) -> Image:
    divided = self.__div__(other)
    vimg2 = divided.vimg.round(VipsOperationRound.FLOOR)
    return Image(self.arena, vimg2)

  def __mod__(self, other: Image | int | float | list[int | float]) -> Image:
    if isinstance(other, Image):
      vimg2 = self.vimg.remainder(other.vimg)
    elif isinstance(other, list):
      vimg2 = self.vimg.modulo(VImage.newFromArray(self.arena, other))
    else:
      vimg2 = self.vimg.modulo(VImage.newFromArray(self.arena, [other]))
    return Image(self.arena, vimg2)

  def _relational(self, other: RelationalOther, op: type[VipsOperationRelational]) -> Image:
    if isinstance(other, Image):
      vimg2 = self.vimg.relational(other.vimg, op)
    else:
      if isinstance(other, (int, float)):
        other = [other]
      vimg2 = self.vimg.relationalConst(op, [float(x) for x in other])
    return Image(self.arena, vimg2)

  def __lt__(self, other: RelationalOther) -> Image:
    op = VipsOperationRelational.OPERATION_RELATIONAL_LESS
    return self._relational(other, op)

  def __le__(self, other: RelationalOther) -> Image:
    op = VipsOperationRelational.OPERATION_RELATIONAL_LESSEQ
    return self._relational(other, op)

  def __gt__(self, other: RelationalOther) -> Image:
    op = VipsOperationRelational.OPERATION_RELATIONAL_MORE
    return self._relational(other, op)

  def __ge__(self, other: RelationalOther) -> Image:
    op = VipsOperationRelational.OPERATION_RELATIONAL_MOREEQ
    return self._relational(other, op)

  def __eq__(self, other: RelationalOther) -> Image:  # type: ignore
    op = VipsOperationRelational.OPERATION_RELATIONAL_EQUAL
    return self._relational(other, op)

  def __ne__(self, other: RelationalOther) -> Image:  # type: ignore
    op = VipsOperationRelational.OPERATION_RELATIONAL_NOTEQ
    return self._relational(other, op)

  def _boolean(self, other: BooleanOther, op: type[VipsOperationBoolean]) -> Image:
    if isinstance(other, Image):
      vimg2 = self.vimg.boolean1(other.vimg, op)
    else:
      if isinstance(other, (int, float)):
        other = [other]
      vimg2 = self.vimg.booleanConst(op, [float(x) for x in other])
    return Image(self.arena, vimg2)

  def __and__(self, other: BooleanOther) -> Image:
    op = VipsOperationBoolean.OPERATION_BOOLEAN_AND
    return self._boolean(other, op)

  def __or__(self, other: BooleanOther) -> Image:
    op = VipsOperationBoolean.OPERATION_BOOLEAN_OR
    return self._boolean(other, op)

  def __xor__(self, other: BooleanOther) -> Image:
    op = VipsOperationBoolean.OPERATION_BOOLEAN_EOR
    return self._boolean(other, op)

  def __lshift__(self, other: BooleanOther) -> Image:
    op = VipsOperationBoolean.OPERATION_BOOLEAN_LSHIFT
    return self._boolean(other, op)

  def __rshift__(self, other: BooleanOther) -> Image:
    op = VipsOperationBoolean.OPERATION_BOOLEAN_RSHIFT
    return self._boolean(other, op)

  def __getitem__(self, arg: slice | int | list[int] | list[bool]):
    i = -1
    band_seq = []
    if isinstance(arg, int):
      # raises IndexError for bad integer indices
      i = range(self.bands)[arg]
      n = 1
    elif isinstance(arg, slice):
      band_seq = range(self.bands)[arg]
      if len(band_seq) == 0:
        raise IndexError("empty slice")

      i = band_seq[0]
      n = len(band_seq)
    elif isinstance(arg, list):
      if not arg:
        raise IndexError("empty list")

      # n.b. We don't use isinstance because isinstance(True, int)
      # is True!
      if not (
          all(isinstance(x, int) for x in arg)
          or all(isinstance(x, bool) for x in arg)
      ):
        raise IndexError("list must contain only ints or only bools")

      if isinstance(arg[0], bool):
        if len(arg) != self.bands:
          raise IndexError(
              "boolean index must have same " + "length as bands"
          )

        band_seq = [i for i, x in enumerate(arg) if x]
        n = len(band_seq)
        if n == 0:
          raise IndexError("empty boolean index")
        if n == 1:
          i = band_seq[0]
      else:
        all_bands = range(self.bands)
        # raises IndexError for bad int indices
        band_seq = [all_bands[i] for i in arg]

        n = len(band_seq)
        if n == 1:
          i = band_seq[0]
    else:
      raise IndexError(
          "argument must be an int, slice, " + "list of ints, or list of bools"
      )

    if n == 1:
      # int or 1-element selection
      return VImage(self.arena, self.vimg.extractBand(i))
    elif isinstance(arg, slice) and arg.step == 1:
      # sequential multi-band slice
      return VImage(self.arena,
                    self.vimg.extractBand(i, VipsOption.Int("n", n)))
    else:
      # nonsequential slice, list of ints, or list of bools
      bands = [Image(self.arena, self.vimg.extractBand(x)) for x in band_seq]
      return VImage.bandjoin(self.arena, bands)
