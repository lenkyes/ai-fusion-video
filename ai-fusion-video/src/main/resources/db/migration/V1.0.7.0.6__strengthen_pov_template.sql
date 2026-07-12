UPDATE `afv_video_template`
SET `config_json` = JSON_SET(
  `config_json`,
  '$.protagonist', JSON_OBJECT(
    'role', 'camera_holder',
    'visualAssetPolicy', 'partial_only',
    'allowedVisibility', JSON_ARRAY('手部', '腿部', '局部倒影', '模糊影子'),
    'forbidStandardCharacterPortrait', TRUE
  ),
  '$.storyPrompt', '随机创作一个真实、克制的第一人称生活故事。故事中的“我”是摄像机持有者和第一人称叙事者，不是普通出镜角色。不得为“我”创建标准人物立绘、正脸参考图或全身角色资产，不要把“我”传给角色资产创建工具。所有场次使用摄像机所见和主观动作描述；只允许偶尔出现手部、腿部、局部倒影或模糊影子。其他被观察人物可以正常创建角色资产。用具体动作和物件承载情绪，不解释道理，不写鸡汤；每次更换人物关系、地点、关键物件与反转。'
),
`version` = `version` + 1
WHERE `code` = 'pov-lyrical-memory' AND `deleted` = 0;

UPDATE `afv_project`
SET `properties` = JSON_SET(
  `properties`,
  '$.videoTemplateSnapshot.protagonist', JSON_OBJECT(
    'role', 'camera_holder',
    'visualAssetPolicy', 'partial_only',
    'allowedVisibility', JSON_ARRAY('手部', '腿部', '局部倒影', '模糊影子'),
    'forbidStandardCharacterPortrait', TRUE
  ),
  '$.videoTemplateSnapshot.storyPrompt', '随机创作一个真实、克制的第一人称生活故事。故事中的“我”是摄像机持有者和第一人称叙事者，不是普通出镜角色。不得为“我”创建标准人物立绘、正脸参考图或全身角色资产，不要把“我”传给角色资产创建工具。所有场次使用摄像机所见和主观动作描述；只允许偶尔出现手部、腿部、局部倒影或模糊影子。其他被观察人物可以正常创建角色资产。用具体动作和物件承载情绪，不解释道理，不写鸡汤。'
)
WHERE JSON_UNQUOTE(JSON_EXTRACT(`properties`, '$.videoTemplateId')) = 'pov-lyrical-memory'
  AND `deleted` = 0;
